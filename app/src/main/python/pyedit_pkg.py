"""
On-device installer. Sources, in order:
  1. PyPI pure-Python wheels (checksum verified).
  2. Chaquopy's Android wheel index (native packages such as numpy).
Everything is staged first and only moved into place if the whole install
(including dependencies) succeeded, so a failure can't leave a half install.
"""
import hashlib
import importlib
import importlib.machinery
import json
import os
import re
import shutil
import site as _site
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import zipfile

UA = "PyEdit-Android/1.0 (package installer)"
CHAQUOPY_INDEX = "https://chaquo.com/pypi-13.1/"
MAX_PACKAGES = 40
PY_TAG = "cp%d%d" % sys.version_info[:2]


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


# --- compatibility ----------------------------------------------------------

def _abi():
    m = (os.uname().machine if hasattr(os, "uname") else "").lower()
    return {
        "aarch64": "arm64_v8a", "arm64": "arm64_v8a",
        "armv7l": "armeabi_v7a", "armv8l": "armeabi_v7a",
        "x86_64": "x86_64", "i686": "x86", "i386": "x86",
    }.get(m, m)


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


_WHEEL_RE = re.compile(
    r"^(?P<name>.+?)-(?P<ver>[^-]+)(?:-(?P<build>\d[^-]*))?-(?P<py>[^-]+)-(?P<abi>[^-]+)-(?P<plat>[^-]+)\.whl$")


def _vkey(v):
    return tuple(int(x) for x in re.findall(r"\d+", v))


def _chaquopy_wheel(name, api_level):
    """Best Android wheel for this Python + ABI, or None (404 / no match)."""
    base = CHAQUOPY_INDEX + _norm(name) + "/"
    try:
        with _get(base, 15) as r:
            html = r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise InstallError("Android wheel index returned HTTP %d" % e.code)
    except OSError as e:
        raise InstallError("Network error while checking Android builds: %s" % e)

    abi = _abi()
    best = None
    for href in re.findall(r'href="([^"]+\.whl)"', html):
        fn = urllib.parse.unquote(href.rsplit("/", 1)[-1])
        m = _WHEEL_RE.match(fn)
        if not m or m.group("py") != PY_TAG or m.group("abi") != PY_TAG:
            continue
        pm = re.fullmatch(r"android_(\d+)_(\w+)", m.group("plat"))
        if not pm or pm.group(2) != abi or int(pm.group(1)) > api_level:
            continue
        key = (_vkey(m.group("ver")), int(m.group("build") or 0))
        if best is None or key > best[0]:
            best = (key, fn, urllib.parse.urljoin(base, href), m.group("ver"))
    return best


def _find_source(name, api_level):
    data = _fetch_json(name)
    info = data.get("info", {})
    real = info.get("name", name)
    f = _pick_wheel(data)
    if f is not None:
        sha = (f.get("digests") or {}).get("sha256")
        if not sha:
            raise InstallError("PyPI gave no checksum for %s" % f.get("filename"))
        return {"kind": "pypi", "name": real, "version": info.get("version", ""),
                "url": f["url"], "filename": f["filename"], "sha256": sha,
                "requires": info.get("requires_dist") or []}
    a = _chaquopy_wheel(real, api_level)
    if a is not None:
        return {"kind": "android", "name": real, "version": a[3], "url": a[2],
                "filename": a[1], "sha256": None, "requires": None}
    raise InstallError(
        "'%s' has no pure-Python wheel and no Android build for Python %d.%d on %s. "
        "This package is incompatible with this device."
        % (real, sys.version_info[0], sys.version_info[1], _abi()))


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
    return {"==": l == r, "!=": l != r, "<=": l <= r,
            ">=": l >= r, "<": l < r, ">": l > r}[op]


def _marker(marker):
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


# --- download / extract / verify / commit -----------------------------------

def _download(url, dest, expected_sha256):
    h = hashlib.sha256()
    try:
        with _get(url, 60) as r, open(dest, "wb") as out:
            while True:
                chunk = r.read(65536)
                if not chunk:
                    break
                h.update(chunk)
                out.write(chunk)
    except OSError as e:
        raise InstallError("Download failed: %s" % e)
    if expected_sha256 and h.hexdigest() != expected_sha256:
        raise InstallError("Checksum mismatch - the download was discarded")


def _wheel_requires(path):
    with zipfile.ZipFile(path) as z:
        for n in z.namelist():
            if n.endswith(".dist-info/METADATA") and n.count("/") == 1:
                text = z.read(n).decode("utf-8", "replace")
                return [l[len("Requires-Dist:"):].strip()
                        for l in text.splitlines() if l.startswith("Requires-Dist:")]
    return []


def _extract(whl_path, stage):
    real_stage = os.path.realpath(stage)
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
                    continue
            else:
                rel = name
            dest = os.path.realpath(os.path.join(stage, rel))
            if not dest.startswith(real_stage + os.sep):
                raise InstallError("Wheel contains an unsafe path: %s" % name)
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with z.open(info) as src, open(dest, "wb") as dst:
                shutil.copyfileobj(src, dst)
            if rel.endswith(".so") or ".so." in rel:
                os.chmod(dest, 0o444)  # Android 14+ dislikes writable native libs
            first = rel.split("/")[0]
            if first.endswith(".dist-info"):
                dist_info = first
            elif not first.endswith(".data") and first != "__pycache__":
                tops.add(first)
    return dist_info, tops


def _verify_stage(stage, dist_info, tops):
    if not dist_info or not os.path.isdir(os.path.join(stage, dist_info)):
        raise InstallError("Verification failed: the wheel had no metadata folder")
    importlib.invalidate_caches()
    for top in tops:
        path = os.path.join(stage, top)
        if not os.path.exists(path):
            raise InstallError("Verification failed: '%s' is missing after extraction" % top)
        if os.path.isdir(path) or top.endswith((".py", ".so", ".pyd")):
            mod = top.split(".")[0]
            if mod.isidentifier() and importlib.machinery.PathFinder.find_spec(mod, [stage]) is None:
                raise InstallError("Verification failed: module '%s' cannot be found" % mod)


def _merge(src, dst):
    for root, _dirs, files in os.walk(src):
        rel = os.path.relpath(root, src)
        target = dst if rel == "." else os.path.join(dst, rel)
        os.makedirs(target, exist_ok=True)
        for f in files:
            d = os.path.join(target, f)
            if os.path.lexists(d):
                os.remove(d)
            shutil.move(os.path.join(root, f), d)


def _commit(stage, site_dir, staged):
    for dist_info, _tops, key in staged:
        _remove_old_dist_info(site_dir, key)
    for entry in os.listdir(stage):
        src = os.path.join(stage, entry)
        dst = os.path.join(site_dir, entry)
        if os.path.isdir(src):
            os.makedirs(dst, exist_ok=True)
            _merge(src, dst)
        else:
            if os.path.lexists(dst):
                os.remove(dst)
            shutil.move(src, dst)


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

    src = _find_source(name, st["api"])
    path = os.path.join(st["tmp"], src["filename"])
    try:
        _download(src["url"], path, src["sha256"])
        requires = src["requires"] if src["requires"] is not None else _wheel_requires(path)

        for line in requires:
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
                    raise InstallError("Dependency '%s' of '%s' failed: %s" % (dep, src["name"], e))

        dist_info, tops = _extract(path, st["stage"])
    except zipfile.BadZipFile:
        raise InstallError("%s is not a valid wheel file" % src["filename"])

    st["staged"].append((dist_info, tops, key))
    st["installed"].append("%s %s" % (src["name"], src["version"]))
    if src["kind"] == "android":
        st["native"].append("%s %s" % (src["name"], src["version"]))


def _device_api():
    """Real API level of the running device (used only if the caller didn't pass one)."""
    try:
        from java import jclass
        return int(jclass("android.os.Build$VERSION").SDK_INT)
    except Exception:
        return 21  # lowest level Chaquopy publishes wheels for; used only if the read fails


def install(name, site_dir, api_level=None):
    """Returns (ok, message). Never raises."""
    if api_level is None:
        api_level = _device_api()
    os.makedirs(site_dir, exist_ok=True)
    stage = tempfile.mkdtemp(prefix=".stage-", dir=site_dir)
    tmp = tempfile.mkdtemp(prefix=".dl-", dir=site_dir)
    st = {"seen": set(), "installed": [], "skipped": [], "staged": [], "native": [],
          "stage": stage, "tmp": tmp, "api": int(api_level)}
    try:
        _install_one(name, site_dir, st, True)
        for dist_info, tops, _key in st["staged"]:
            _verify_stage(stage, dist_info, tops)
        _commit(stage, site_dir, st["staged"])
        if site_dir not in sys.path:
            _site.addsitedir(site_dir)
        importlib.invalidate_caches()
    except InstallError as e:
        return False, str(e)
    except Exception as e:
        return False, "Unexpected error: %s" % e
    finally:
        shutil.rmtree(stage, ignore_errors=True)
        shutil.rmtree(tmp, ignore_errors=True)

    msg = "Installed " + st["installed"][-1]
    if len(st["installed"]) > 1:
        msg += " (with %s)" % ", ".join(st["installed"][:-1])
    msg += ". Files were verified but the package was not imported - try it in a script."
    if st["native"]:
        msg += (" Native Android build(s): %s. The Android wheel index publishes no checksums, "
                "so these were fetched over HTTPS only." % ", ".join(st["native"]))
    if st["skipped"]:
        msg += " Optional conditional dependencies skipped: %s." % ", ".join(st["skipped"])
    return True, msg