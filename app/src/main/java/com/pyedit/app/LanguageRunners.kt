package com.pyedit.app

import app.cash.quickjs.QuickJs
import app.cash.quickjs.QuickJsException
import org.json.JSONObject
import org.luaj.vm2.LuaError
import org.luaj.vm2.lib.jse.JsePlatform
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream

/** Bound into QuickJS as the global `__host`. */
interface HostBridge {
    fun write(stream: Int, text: String)
    fun readLine(): String
    /** 0 = missing, 1 = file, 2 = directory (virtual /node_modules/... paths only) */
    fun stat(path: String): Int
    fun readFile(path: String): String
}

private val LINE_REGEX = Regex("^.*?:(\\d+):\\s*(.*)", RegexOption.DOT_MATCHES_ALL)

object LuaRunner {

    fun run(
        source: String,
        fileName: String,
        out: (String) -> Unit,
        err: (String) -> Unit,
        readInput: () -> String,
        report: (line: Int, type: String, message: String) -> Unit
    ) {
        val globals = JsePlatform.standardGlobals()
        val stdout = PrintStream(SinkStream(out), true, "UTF-8")
        val stderr = PrintStream(SinkStream(err), true, "UTF-8")
        globals.STDOUT = stdout
        globals.STDERR = stderr
        globals.STDIN = LineInputStream(readInput)
        try {
            globals.load(source, "@$fileName").call()
        } catch (e: LuaError) {
            val text = e.message ?: "Lua error"
            val m = LINE_REGEX.find(text)
            if (m != null) report(m.groupValues[1].toInt(), "LuaError", m.groupValues[2].trim())
            else report(0, "LuaError", text)
        } finally {
            stdout.flush()
            stderr.flush()
        }
    }
}

object JsRunner {

    /**
     * console / print / prompt, a small `process`, and a CommonJS require().
     * require() only reads from the installed-packages folder through the host
     * bridge (virtual root /node_modules). Node built-ins are refused with a
     * clear message. NOTE: no dollar signs in here (Kotlin raw string).
     */
    private const val PRELUDE = """
(function () {
  var h = globalThis.__host;
  function fmt(v) {
    if (typeof v === 'string') return v;
    try { if (typeof v === 'object' && v !== null) return JSON.stringify(v); } catch (e) {}
    return String(v);
  }
  function join(a) { return Array.prototype.map.call(a, fmt).join(' '); }
  var log = function () { h.write(1, join(arguments) + "\n"); };
  var errf = function () { h.write(2, join(arguments) + "\n"); };
  globalThis.console = { log: log, info: log, debug: log, warn: errf, error: errf };
  globalThis.print = log;
  globalThis.readline = function () { return h.readLine(); };
  globalThis.prompt = function (m) { if (m) h.write(1, String(m)); return h.readLine(); };
  globalThis.global = globalThis;
  globalThis.process = {
    env: {}, argv: ['quickjs'], platform: 'android', version: 'v0.0.0', versions: {},
    cwd: function () { return '/'; },
    nextTick: function (f) { f.apply(null, Array.prototype.slice.call(arguments, 1)); },
    emitWarning: function () {},
    stdout: { write: function (s) { h.write(1, String(s)); return true; } },
    stderr: { write: function (s) { h.write(2, String(s)); return true; } }
  };

  var BUILTIN = {};
  ('fs path os util events http https http2 crypto stream buffer child_process assert url zlib net tls dgram ' +
   'readline querystring string_decoder timers vm worker_threads cluster dns module tty v8 perf_hooks ' +
   'async_hooks process console constants punycode repl domain inspector').split(' ').forEach(function (n) { BUILTIN[n] = true; });

  var cache = Object.create(null);
  var CONDS = ['require', 'node', 'default'];

  function dirname(p) { var i = p.lastIndexOf('/'); return i <= 0 ? '/' : p.slice(0, i); }
  function normalize(p) {
    var parts = p.split('/'), out = [];
    for (var i = 0; i < parts.length; i++) {
      var s = parts[i];
      if (s === '' || s === '.') continue;
      if (s === '..') { out.pop(); continue; }
      out.push(s);
    }
    return '/' + out.join('/');
  }
  function joinPath(a, b) { return normalize(a + '/' + b); }
  function isFile(p) { return h.stat(p) === 1; }
  function isDir(p) { return h.stat(p) === 2; }
  function readJson(p) { try { return JSON.parse(h.readFile(p)); } catch (e) { return null; } }

  function resolveFile(p) {
    var c = [p, p + '.js', p + '.json', p + '.cjs'];
    for (var i = 0; i < c.length; i++) if (isFile(c[i])) return c[i];
    return null;
  }
  function resolveDir(p) {
    var pkg = readJson(p + '/package.json');
    if (pkg && typeof pkg.main === 'string' && pkg.main) {
      var m = joinPath(p, pkg.main);
      var r = resolveFile(m) || (isDir(m) ? resolveFile(m + '/index') : null);
      if (r) return r;
    }
    return resolveFile(p + '/index');
  }
  function nodeModulesPaths(from) {
    var parts = from.split('/').filter(function (s) { return s !== ''; });
    var out = [];
    for (var i = parts.length; i >= 0; i--) {
      if (i > 0 && parts[i - 1] === 'node_modules') continue;
      out.push('/' + parts.slice(0, i).join('/') + '/node_modules');
    }
    return out.map(function (p) { return p.replace('//', '/'); });
  }
  function pickTarget(t) {
    if (typeof t === 'string') return t;
    if (Array.isArray(t)) { for (var i = 0; i < t.length; i++) { var r = pickTarget(t[i]); if (r) return r; } return null; }
    if (t && typeof t === 'object') {
      for (var k in t) { if (CONDS.indexOf(k) >= 0) { var r2 = pickTarget(t[k]); if (r2) return r2; } }
    }
    return null;
  }
  function resolveExports(dir, ex, sub) {
    var map = ex;
    var isMap = ex && typeof ex === 'object' && !Array.isArray(ex);
    if (isMap) { var keys = Object.keys(ex); isMap = keys.length > 0 && keys[0].charAt(0) === '.'; }
    if (!isMap) { if (sub !== '.') return null; map = { '.': ex }; }
    var t = null;
    if (Object.prototype.hasOwnProperty.call(map, sub)) t = pickTarget(map[sub]);
    else {
      for (var key in map) {
        var star = key.indexOf('*');
        if (star < 0) continue;
        var pre = key.slice(0, star), post = key.slice(star + 1);
        if (sub.indexOf(pre) === 0 && sub.length >= pre.length + post.length &&
            sub.slice(sub.length - post.length) === post) {
          var mid = sub.slice(pre.length, sub.length - post.length);
          var tt = pickTarget(map[key]);
          if (tt) { t = tt.split('*').join(mid); break; }
        }
      }
    }
    return t ? joinPath(dir, t) : null;
  }
  function splitSpec(spec) {
    var parts = spec.split('/');
    var n = spec.charAt(0) === '@' ? 2 : 1;
    return [parts.slice(0, n).join('/'), parts.length > n ? './' + parts.slice(n).join('/') : '.'];
  }
  function resolveBare(spec, fromDir) {
    var sp = splitSpec(spec), name = sp[0], sub = sp[1];
    var dirs = nodeModulesPaths(fromDir);
    for (var i = 0; i < dirs.length; i++) {
      var pdir = dirs[i] + '/' + name;
      if (!isDir(pdir)) continue;
      var pkg = readJson(pdir + '/package.json');
      if (pkg && pkg.exports !== undefined && pkg.exports !== null) {
        var r = resolveExports(pdir, pkg.exports, sub);
        if (r && isFile(r)) return r;
        throw new Error("Package subpath '" + sub + "' is not exported by '" + name + "'");
      }
      if (sub === '.') { var f = resolveDir(pdir); if (f) return f; }
      else {
        var p2 = joinPath(pdir, sub);
        var f2 = resolveFile(p2) || (isDir(p2) ? resolveDir(p2) : null);
        if (f2) return f2;
      }
    }
    return null;
  }
  function looksEsm(src) { return /^\s*(import\s|export\s)/m.test(src); }

  function loadFile(file) {
    if (cache[file]) return cache[file].exports;
    var mod = { exports: {}, id: file, filename: file, loaded: false };
    cache[file] = mod;
    var src = h.readFile(file);
    if (/\.json$/.test(file)) { mod.exports = JSON.parse(src); mod.loaded = true; return mod.exports; }
    if (src.charCodeAt(0) === 0xFEFF) src = src.slice(1);
    if (src.indexOf('#!') === 0) src = '//' + src;
    var fn;
    try {
      fn = new Function('exports', 'require', 'module', '__filename', '__dirname', src + '\n//# sourceURL=' + file);
    } catch (e) {
      delete cache[file];
      throw new Error("Cannot load '" + file + "': " + e.message +
        (looksEsm(src) ? ' (this package uses ES module syntax, which is not supported)' : ''));
    }
    try {
      fn.call(mod.exports, mod.exports, makeRequire(dirname(file)), mod, file, dirname(file));
    } catch (e) { delete cache[file]; throw e; }
    mod.loaded = true;
    return mod.exports;
  }
  function load(spec, fromDir) {
    var s = spec.indexOf('node:') === 0 ? spec.slice(5) : spec;
    if (BUILTIN[s.split('/')[0]] === true) {
      throw new Error("Node.js built-in module '" + s + "' is not available in this script engine");
    }
    var file = null;
    if (spec.charAt(0) === '.' || spec.charAt(0) === '/') {
      if (fromDir === '/') {
        throw new Error("Cannot load '" + spec + "': importing your own files is not supported; only installed packages can be required");
      }
      var p = spec.charAt(0) === '/' ? normalize(spec) : joinPath(fromDir, spec);
      file = resolveFile(p) || (isDir(p) ? resolveDir(p) : null);
    } else {
      file = resolveBare(spec, fromDir);
    }
    if (!file) {
      throw new Error("Cannot find module '" + spec + "'. Install it from Download library (JavaScript or TypeScript tab).");
    }
    return loadFile(file);
  }
  function makeRequire(fromDir) { return function (spec) { return load(String(spec), fromDir); }; }

  globalThis.module = { exports: {} };
  globalThis.exports = globalThis.module.exports;
  globalThis.require = makeRequire('/');
})();
"""

    private val nodeRoot: File? by lazy {
        System.getenv("PYEDIT_NODE_MODULES")?.let { runCatching { File(it).canonicalFile }.getOrNull() }
    }

    private fun virtualToReal(path: String): File? {
        val root = nodeRoot ?: return null
        if (path != "/node_modules" && !path.startsWith("/node_modules/")) return null
        val f = runCatching { File(root, path.removePrefix("/node_modules")).canonicalFile }.getOrNull() ?: return null
        return if (f.path == root.path || f.path.startsWith(root.path + File.separator)) f else null
    }

    /**
     * @param exactLines false for transpiled TypeScript: line numbers then
     * refer to the compiled JS, so no editor jump is requested.
     */
    fun run(
        source: String,
        fileName: String,
        exactLines: Boolean,
        out: (String) -> Unit,
        err: (String) -> Unit,
        readInput: () -> String,
        report: (line: Int, type: String, message: String) -> Unit
    ) {
        val qjs = QuickJs.create()
        try {
            val bridge = object : HostBridge {
                override fun write(stream: Int, text: String) {
                    if (stream == 2) err(text) else out(text)
                }
                override fun readLine(): String = readInput()
                override fun stat(path: String): Int {
                    val f = virtualToReal(path) ?: return 0
                    return if (f.isDirectory) 2 else if (f.isFile) 1 else 0
                }
                override fun readFile(path: String): String {
                    val f = virtualToReal(path)?.takeIf { it.isFile && it.length() < 8_000_000 } ?: return ""
                    return runCatching { f.readText() }.getOrDefault("")
                }
            }
            qjs.set("__host", HostBridge::class.java, bridge)
            qjs.evaluate(PRELUDE, "prelude.js")
            qjs.evaluate(source, fileName)
        } catch (e: QuickJsException) {
            var text = e.message ?: "JavaScript error"
            var line = e.stackTrace.firstOrNull { it.fileName == fileName }?.lineNumber ?: 0
            if (line <= 0) {
                line = Regex("${Regex.escape(fileName)}:(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }
            val type = Regex("^(\\w*Error)\\b").find(text)?.groupValues?.get(1) ?: "Error"
            if (exactLines && text.contains("unexpected token", ignoreCase = true) &&
                Regex("^\\s*(import|export)\\b", RegexOption.MULTILINE).containsMatchIn(source)
            ) {
                text += "\n(ES module import/export isn't supported in .js files - use require(), or write the file as .ts)"
            }
            if (exactLines) report(line, type, text)
            else report(0, type, if (line > 0) "$text (compiled JS line $line)" else text)
        } finally {
            qjs.close()
        }
    }
}

object TsTranspiler {

    data class Result(val js: String?, val errors: List<Pair<Int, String>>)

    /** Strips types / downlevels with the real TypeScript compiler. No type checking. */
    fun transpile(compilerFile: File, source: String, fileName: String): Result {
        val qjs = QuickJs.create()
        try {
            qjs.evaluate(compilerFile.readText(), "typescript.js")
            qjs.evaluate(
                "var __src = ${JSONObject.quote(source)}; var __name = ${JSONObject.quote(fileName)};",
                "input.js"
            )
            val json = qjs.evaluate(
                """
                (function () {
                  var r = ts.transpileModule(__src, {
                    fileName: __name,
                    reportDiagnostics: true,
                    compilerOptions: {
                      target: ts.ScriptTarget.ES2020,
                      module: ts.ModuleKind.CommonJS,
                      esModuleInterop: true
                    }
                  });
                  var d = (r.diagnostics || []).map(function (x) {
                    var line = (x.file && x.start !== undefined)
                      ? x.file.getLineAndCharacterOfPosition(x.start).line + 1 : 0;
                    return { line: line, msg: ts.flattenDiagnosticMessageText(x.messageText, "\n") };
                  });
                  return JSON.stringify({ out: r.outputText, diags: d });
                })()
                """.trimIndent(),
                "transpile.js"
            ) as String
            val obj = JSONObject(json)
            val diags = obj.getJSONArray("diags")
            val errors = ArrayList<Pair<Int, String>>()
            for (i in 0 until diags.length()) {
                val d = diags.getJSONObject(i)
                errors.add(Pair(d.getInt("line"), d.getString("msg")))
            }
            return Result(if (errors.isEmpty()) obj.getString("out") else null, errors)
        } finally {
            qjs.close()
        }
    }
}

private class SinkStream(private val sink: (String) -> Unit) : OutputStream() {
    private val buf = ByteArrayOutputStream()
    @Synchronized override fun write(b: Int) { buf.write(b) }
    @Synchronized override fun write(b: ByteArray, off: Int, len: Int) { buf.write(b, off, len) }
    @Synchronized override fun flush() {
        if (buf.size() > 0) {
            sink(buf.toString("UTF-8"))
            buf.reset()
        }
    }
}

private class LineInputStream(private val readInput: () -> String) : InputStream() {
    private var cur = ByteArray(0)
    private var pos = 0

    private fun refill() {
        cur = (readInput() + "\n").toByteArray(Charsets.UTF_8)
        pos = 0
    }

    override fun read(): Int {
        if (pos >= cur.size) refill()
        return cur[pos++].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (pos >= cur.size) refill()
        val n = minOf(len, cur.size - pos)
        System.arraycopy(cur, pos, b, off, n)
        pos += n
        return n
    }
}