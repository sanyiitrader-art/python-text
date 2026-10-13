"""
On-device installer for pure-Python PyPI wheels (Chaquopy has no pip at
runtime). Only wheels tagged py3-none-any (or py3N-none-any, N <= running
minor version) are installable; everything else is refused with a reason.
"""
import hashlib
import importlib
import importlib.util
import json
import os
import re
import shutil
import site as _site
import sys
import tempfile
import urllib.error
import urllib.request
import zipfile

UA = "PyEdit-Android/1.0 (package installer)"
MAX_PACKAGES = 40


class InstallError(Exception):
    pass


def _norm(name):
    return re.sub(r"[-_.]+", "-", name).lower()


def _get(url, timeout):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    return urllib.request.urlopen(req, timeout=timeout)


def _fetch_json(name):
    try:
        with _get("https://pypi.org/pypi/%s/json" % _norm(name), 15) as r:
            return json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        if e.code == 404:
            raise InstallError("'%s' was not found on PyPI" % name)
        raise InstallError("PyPI returned HTTP %d for '%s'" % (e.code, name))
    except OSError as e:
        raise InstallError("Network error while fetching '%s': %s" % (name, e))
    except ValueError:
        raise InstallError("Malformed response from PyPI for '%s'" % name)


# --- compatibility checks ---------------------------------------------------

def _tag_ok(py_tags, abi, plat):
    if abi != "none" or plat != "any":
        return False
    for t in py_tags.split("."):
        if t == "py3":
            return True
        m = re.fullmatch(r"py3(\d+)", t)
        if m and int(m.group(1)) <= sys.version_info[1]:
            return True
    return False


def _py_ok(spec):
    """Checks a requires_python string; unparseable parts never block."""
    if not spec:
        return True
    cur = tuple(sys.version_info[:3])
    for part in spec.split(","):
        m = re.fullmatch(r"\s*(>=|<=|==|!=|>|<|~=)\s*([0-9]+(?:\.[0-9]+)*)(\.\*)?\s*", part)
        if not m:
            continue
        op, ver, wild = m.groups()
        v = tuple(int(x) for x in ver.split("."))
        if wild:
            match = cur[:len(v)] == v
            if (op == "==" and not match) or (op == "!=" and match):
                return False
            continue
        v3 = (v + (0, 0, 0))[:3]
        if op in (">=", "~=") and not cur >= v3:
            return False
        if op == "<=" and not cur <= v3:
            return False
        if op == ">" and not cur > v3:
            return False
        if op == "<" and not cur < v3:
            return False
        if op == "==" and cur != v3:
            return False
        if op == "!=" and cur == v3:
            return False
    return True


def _pick_wheel(data):
    for f in data.get("urls", []):
        fn = f.get("filename", "")
        if not fn.endswith(".whl"):
            continue
        parts = fn[:-4].split("-")
        if len(parts) < 5:
            continue
        if _tag_ok(parts[-3], parts[-2], parts[-1]) and _py_ok(f.get("requires_python")):
            return f
    return None


# --- dependency markers (tiny evaluator, no eval) ---------------------------

_ENV = {
    "python_version": "%d.%d" % sys.version_info[:2],
    "python_full_version": "%d.%d.%d" % tuple(sys.version_info[:3]),
    "sys_platform": sys.platform,
    "platform_system": "Linux",
    "os_name": os.name,
    "implementation_name": sys.implementation.name,
    "platform_python_implementation": "CPython",
}


def _vt(s):
    try:
        return tuple(int(x) for x in s.split("."))
    except ValueError:
        return None


def _clause(c):
    m = re.fullmatch(r"\s*([a-z_]+)\s*(==|!=|<=|>=|<|>)\s*['\"]([^'\"]*)['\"]\s*", c)
    if not m or m.group(1) not in _ENV:
        return None
    var, op, val = m.groups()
    left = _ENV[var]
    if var in ("python_version", "python_full_version"):
        l, r = _vt(left), _vt(val)
        if l is None or r is None:
            return None
    else:
        l, r = left, val
    return {
        "==": l == r, "!=": l != r, "<=": l <= r,
        ">=": l >= r, "<": l < r, ">": l > r,
    }[op]


def _marker(marker):
    """True / False, or None when it can't be evaluated safely."""
    if "(" in marker:
        return None
    result = False
    for orpart in marker.split(" or "):
        res = True
        for c in orpart.split(" and "):
            v = _clause(c)
            if v is None:
                return None
            res = res and v
        result = result or res
    return result


def _parse_req(line):
    req, _, marker = line.partition(";")
    m = re.match(r"\s*([A-Za-z0-9][A-Za-z0-9._-]*)", req)
    if not m:
        return None
    return m.group(1), marker.strip()


# --- installed packages -----------------------------------------------------

def _installed_dists(site_dir):
    out = {}
    try:
        entries = os.listdir(site_dir)
    except OSError:
        return out
    for e in entries:
        if e.endswith(".dist-info"):
            name, _, ver = e[:-len(".dist-info")].rpartition("-")
            if name:
                out[_norm(name)] = ver
    return out


def list_installed(site_dir):
    return _installed_dists(site_dir)


# --- extraction / verification ---------------------------------------------

def _extract(whl_path, site_dir):
    real_site = os.path.realpath(site_dir)
    tops = set()
    dist_info = None
    with zipfile.ZipFile(whl_path) as z:
        for info in z.infolist():
            name = info.filename
            if name.endswith("/"):
                continue
            parts = name.split("/")
            if parts[0].endswith(".data"):
                if len(parts) > 2 and parts[1] in ("purelib", "platlib"):
                    rel = "/".join(parts[2:])
                else:
                    continue  # scripts / headers / data are not installed
            else:
                rel = name
            dest = os.path.realpath(os.path.join(site_dir, rel))
            if not dest.startswith(real_site + os.sep):
                raise InstallError("Wheel contains an unsafe path: %s" % name)
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with z.open(info) as src, open(dest, "wb") as dst:
                shutil.copyfileobj(src, dst)
            first = rel.split("/")[0]
            if first.endswith(".dist-info"):
                dist_info = first
            elif not first.endswith(".data") and first != "__pycache__":
                tops.add(first)
    return dist_info, tops


def _remove_old_dist_info(site_dir, key):
    try:
        entries = os.listdir(site_dir)
    except OSError:
        return
    for e in entries:
        if e.endswith(".dist-info"):
            name, _, _ = e[:-len(".dist-info")].rpartition("-")
            if _norm(name) == key:
                shutil.rmtree(os.path.join(site_dir, e), ignore_errors=True)


def _verify(site_dir, dist_info, tops):
    if not dist_info or not os.path.isdir(os.path.join(site_dir, dist_info)):
        raise InstallError("Verification failed: the wheel had no metadata folder")
    if _site_dir_not_on_path(site_dir):
        _site.addsitedir(site_dir)
    importlib.invalidate_caches()
    for top in tops:
        mod = top[:-3] if top.endswith(".py") else top
        if not os.path.exists(os.path.join(site_dir, top)):
            raise InstallError("Verification failed: '%s' is missing after extraction" % top)
        if mod.isidentifier() and importlib.util.find_spec(mod) is None:
            raise InstallError("Verification failed: module '%s' cannot be found" % mod)


def _site_dir_not_on_path(site_dir):
    return site_dir not in sys.path


# --- install ----------------------------------------------------------------

def _install_one(name, site_dir, st, is_root):
    key = _norm(name)
    if key in st["seen"]:
        return
    st["seen"].add(key)
    if len(st["seen"]) > MAX_PACKAGES:
        raise InstallError("Too many dependencies (limit %d)" % MAX_PACKAGES)
    if not is_root and key in _installed_dists(site_dir):
        return

    data = _fetch_json(name)
    info = data.get("info", {})
    f = _pick_wheel(data)
    if f is None:
        raise InstallError(
            "'%s' has no pure-Python wheel compatible with Python %d.%d. "
            "Native or source-only packages can't be installed on this device."
            % (info.get("name", name), sys.version_info[0], sys.version_info[1]))

    # Dependencies first, so a failing dependency doesn't leave the root half-installed.
    for line in info.get("requires_dist") or []:
        parsed = _parse_req(line)
        if not parsed:
            continue
        dep, marker = parsed
        if "extra" in marker:
            continue
        unknown = False
        if marker:
            verdict = _marker(marker)
            if verdict is False:
                continue
            unknown = verdict is None
        try:
            _install_one(dep, site_dir, st, False)
        except InstallError as e:
            if unknown:
                st["skipped"].append(dep)
            else:
                raise InstallError("Dependency '%s' of '%s' failed: %s"
                                   % (dep, info.get("name", name), e))

    expected = (f.get("digests") or {}).get("sha256")
    if not expected:
        raise InstallError("PyPI gave no checksum for %s" % f.get("filename"))

    tmp = tempfile.mkdtemp(prefix=".pyedit-", dir=site_dir)
    try:
        path = os.path.join(tmp, f["filename"])
        h = hashlib.sha256()
        try:
            with _get(f["url"], 60) as r, open(path, "wb") as out:
                while True:
                    chunk = r.read(65536)
                    if not chunk:
                        break
                    h.update(chunk)
                    out.write(chunk)
        except OSError as e:
            raise InstallError("Download of %s failed: %s" % (f["filename"], e))
        if h.hexdigest() != expected:
            raise InstallError("Checksum mismatch for %s - download discarded" % f["filename"])
        _remove_old_dist_info(site_dir, key)
        dist_info, tops = _extract(path, site_dir)
        _verify(site_dir, dist_info, tops)
    except zipfile.BadZipFile:
        raise InstallError("%s is not a valid wheel file" % f.get("filename"))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    st["installed"].append("%s %s" % (info.get("name", name), info.get("version", "")))


def install(name, site_dir):
    """Returns (ok, message). Never raises."""
    os.makedirs(site_dir, exist_ok=True)
    st = {"seen": set(), "installed": [], "skipped": []}
    try:
        _install_one(name, site_dir, st, True)
    except InstallError as e:
        return False, str(e)
    except Exception as e:  # unexpected - still report honestly
        return False, "Unexpected error: %s" % e

    msg = "Installed " + st["installed"][-1]
    if len(st["installed"]) > 1:
        msg += " (with %d dependenc%s: %s)" % (
            len(st["installed"]) - 1,
            "y" if len(st["installed"]) == 2 else "ies",
            ", ".join(st["installed"][:-1]))
    msg += ". Files and metadata were verified; the package was not imported."
    if st["skipped"]:
        msg += " Optional conditional dependencies skipped: %s." % ", ".join(st["skipped"])
    return True, msg