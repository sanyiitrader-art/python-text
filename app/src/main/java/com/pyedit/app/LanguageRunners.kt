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
  globalThis.module = { exports: {} };
  globalThis.exports = globalThis.module.exports;
})();
"""

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
            }
            qjs.set("__host", HostBridge::class.java, bridge)
            qjs.evaluate(PRELUDE, "prelude.js")
            qjs.evaluate(source, fileName)
        } catch (e: QuickJsException) {
            val text = e.message ?: "JavaScript error"
            var line = e.stackTrace.firstOrNull { it.fileName == fileName }?.lineNumber ?: 0
            if (line <= 0) {
                line = Regex("${Regex.escape(fileName)}:(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }
            val type = Regex("^(\\w*Error)\\b").find(text)?.groupValues?.get(1) ?: "Error"
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