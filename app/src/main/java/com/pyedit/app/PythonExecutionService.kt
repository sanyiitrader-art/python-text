package com.pyedit.app

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * Runs in the :pyexec process (per manifest). Executes a script via
 * pyedit_runner.py, streaming stdout/stderr back to the UI process over
 * a Messenger, and reading stdin lines the same way. Stop is handled by
 * MainActivity killing this whole process directly (Process.killProcess
 * via ActivityManager on the client side) — not by anything in here —
 * which is what makes Stop instant and unconditional regardless of what
 * the running script is doing.
 */
class PythonExecutionService : Service() {

    private var clientMessenger: Messenger? = null
    private val stdinQueue = LinkedBlockingQueue<String>()

    /** Passed into Python as the stdout/stderr "writer" objects — Chaquopy
     * exposes any public Kotlin method to Python automatically, so
     * pyedit_runner.py can just call .write(s) on these. */
    inner class StreamEmitter(private val what: Int) {
        fun write(s: String) {
            sendToClient(what, s)
        }
    }

    /** Passed into Python as the stdin "reader" — blocks the execution
     * thread (NOT the service's main thread) until a line arrives. */
    inner class StdinReader {
        fun readLine(): String = stdinQueue.take()
    }

    private val incomingHandler = Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            ExecutionProtocol.MSG_REGISTER_CLIENT -> {
                clientMessenger = msg.replyTo
            }
            ExecutionProtocol.MSG_RUN -> {
                clientMessenger = msg.replyTo
                val path = msg.data.getString(ExecutionProtocol.KEY_SCRIPT_PATH)
                if (path != null) runScript(path)
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
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
    }

    private fun runScript(path: String) {
        thread(name = "pyedit-exec") {
            try {
                val py = Python.getInstance()
                val runner = py.getModule("pyedit_runner")
                runner.callAttr(
                    "run_script",
                    path,
                    StreamEmitter(ExecutionProtocol.MSG_STDOUT),
                    StreamEmitter(ExecutionProtocol.MSG_STDERR),
                    StdinReader()
                )
            } catch (t: Throwable) {
                sendToClient(ExecutionProtocol.MSG_STDERR, "Execution error: ${t.message}\n")
            } finally {
                sendToClient(ExecutionProtocol.MSG_EXITED, "")
            }
        }
    }

    private fun sendToClient(what: Int, text: String) {
        val messenger = clientMessenger ?: return
        val msg = Message.obtain(null, what)
        msg.data.putString(ExecutionProtocol.KEY_TEXT, text)
        try {
            messenger.send(msg)
        } catch (t: Throwable) {
            // Client process gone — nothing to do.
        }
    }
}