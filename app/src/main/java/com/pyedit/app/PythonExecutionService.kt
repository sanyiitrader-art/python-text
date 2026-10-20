package com.pyedit.app

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.system.Os
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.util.concurrent.LinkedBlockingQueue

/**
 * Runs every language in the isolated :pyexec process, so Stop (killing the
 * process) works the same way for all of them.
 */
class PythonExecutionService : Service() {

    private var clientMessenger: Messenger? = null
    private val stdinQueue = LinkedBlockingQueue<String>()

    @Volatile
    private var isExecuting = false

    inner class StreamEmitter(private val what: Int) {
        fun write(s: String) {
            sendToClient(what, s)
        }
    }

    inner class StdinReader {
        fun readLine(): String {
            sendToClient(ExecutionProtocol.MSG_INPUT_REQUESTED, "")
            return stdinQueue.take()
        }
    }

    inner class ErrorReporter {
        fun report(line: Int, errorType: String, message: String) {
            val messenger = clientMessenger ?: return
            val msg = Message.obtain(null, ExecutionProtocol.MSG_ERROR)
            msg.data.putInt(ExecutionProtocol.KEY_ERROR_LINE, line)
            msg.data.putString(ExecutionProtocol.KEY_ERROR_TYPE, errorType)
            msg.data.putString(ExecutionProtocol.KEY_ERROR_MESSAGE, message)
            try {
                messenger.send(msg)
            } catch (t: Throwable) { /* client process gone */ }
        }
    }

    private val incomingHandler = Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            ExecutionProtocol.MSG_REGISTER_CLIENT -> {
                clientMessenger = msg.replyTo
                sendToClient(ExecutionProtocol.MSG_STATUS_RESPONSE, isExecuting.toString())
            }
            ExecutionProtocol.MSG_RUN -> {
                clientMessenger = msg.replyTo
                val path = msg.data.getString(ExecutionProtocol.KEY_SCRIPT_PATH)
                val lang = msg.data.getString(ExecutionProtocol.KEY_LANGUAGE) ?: "python"
                val compiler = msg.data.getString(ExecutionProtocol.KEY_COMPILER_PATH)
                val display = msg.data.getString(ExecutionProtocol.KEY_FILE_NAME) ?: "script"
                if (path != null) runScript(path, lang, compiler, display)
            }
            ExecutionProtocol.MSG_STDIN_LINE -> {
                val line = msg.data.getString(ExecutionProtocol.KEY_TEXT) ?: ""
                stdinQueue.offer(line)
            }
        }
        true
    }

    private val messenger = Messenger(incomingHandler)

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onCreate() {
        super.onCreate()
        // Must be set before anything reads them.
        try {
            Os.setenv("PYEDIT_SITE_PACKAGES", File(filesDir, "site-packages").absolutePath, true)
            Os.setenv("PYEDIT_NODE_MODULES", File(filesDir, "node_modules").absolutePath, true)
        } catch (t: Throwable) { /* installed packages just won't be importable */ }

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
    }

    private fun runScript(path: String, lang: String, compilerPath: String?, displayName: String) {
        isExecuting = true
        // Big stack: loading the TypeScript compiler into QuickJS is deep.
        Thread(null, {
            try {
                if (lang == "python") runPython(path) else runOther(path, lang, compilerPath, displayName)
            } catch (t: Throwable) {
                sendToClient(ExecutionProtocol.MSG_STDERR, "Execution error: ${t.message}\n")
            } finally {
                isExecuting = false
                sendToClient(ExecutionProtocol.MSG_EXITED, "")
            }
        }, "pyedit-exec", 32L * 1024 * 1024).start()
    }

    private fun runPython(path: String) {
        val py = Python.getInstance()
        val runner = py.getModule("pyedit_runner")
        runner.callAttr(
            "run_script",
            path,
            StreamEmitter(ExecutionProtocol.MSG_STDOUT),
            StreamEmitter(ExecutionProtocol.MSG_STDERR),
            StdinReader(),
            ErrorReporter()
        )
    }

    private fun runOther(path: String, lang: String, compilerPath: String?, displayName: String) {
        val source = File(path).readText()
        val out = { s: String -> sendToClient(ExecutionProtocol.MSG_STDOUT, s) }
        val err = { s: String -> sendToClient(ExecutionProtocol.MSG_STDERR, s) }
        val stdin = StdinReader()
        val reporter = ErrorReporter()
        val readLine = { stdin.readLine() }
        val report = { line: Int, type: String, message: String -> reporter.report(line, type, message) }

        when (lang) {
            "lua" -> LuaRunner.run(source, displayName, out, err, readLine, report)
            "js" -> JsRunner.run(source, displayName, true, out, err, readLine, report)
            "ts" -> {
                val compiler = compilerPath?.let { File(it) }
                if (compiler == null || !compiler.exists()) {
                    report(0, "RuntimeError", "The TypeScript compiler is missing. Reinstall it from Install other languages.")
                    return
                }
                val result = TsTranspiler.transpile(compiler, source, displayName)
                val js = result.js
                if (js == null) {
                    val first = result.errors.first()
                    report(first.first, "SyntaxError", first.second)
                    return
                }
                val jsName = displayName.replace(Regex("\\.(ts|mts|cts)$", RegexOption.IGNORE_CASE), ".js")
                JsRunner.run(js, jsName, false, out, err, readLine, report)
            }
            else -> report(0, "RuntimeError", "No runtime for '$lang'")
        }
    }

    private fun sendToClient(what: Int, text: String) {
        val messenger = clientMessenger ?: return
        val msg = Message.obtain(null, what)
        msg.data.putString(ExecutionProtocol.KEY_TEXT, text)
        try {
            messenger.send(msg)
        } catch (t: Throwable) { /* client process gone */ }
    }
}