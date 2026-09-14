package com.pyedit.app

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import java.io.File

/**
 * Client-side (UI process) half of execution. Binds to
 * PythonExecutionService in :pyexec, sends the script to run, and
 * forwards stdout/stderr/exit callbacks back to the caller.
 *
 * The current file's content is always copied to a temp .py file in
 * app-private cache storage before running — NOT executed directly from
 * its original SAF content:// location. This sidesteps a real problem:
 * an arbitrary picked folder/file may come from a storage provider with
 * no plain filesystem path at all, or one the separate :pyexec process
 * can't access without its own URI permission grant. A cache-local plain
 * file path always works regardless of where the source file lives.
 */
class ExecutionController(private val context: Context) {

    interface Listener {
        fun onStdout(text: String)
        fun onStderr(text: String)
        fun onExited()
    }

    private var serviceMessenger: Messenger? = null
    private var listener: Listener? = null
    private var bound = false

    private val clientMessenger = Messenger(android.os.Handler(android.os.Looper.getMainLooper()) { msg ->
        when (msg.what) {
            ExecutionProtocol.MSG_STDOUT ->
                listener?.onStdout(msg.data.getString(ExecutionProtocol.KEY_TEXT) ?: "")
            ExecutionProtocol.MSG_STDERR ->
                listener?.onStderr(msg.data.getString(ExecutionProtocol.KEY_TEXT) ?: "")
            ExecutionProtocol.MSG_EXITED ->
                listener?.onExited()
        }
        true
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            serviceMessenger = Messenger(binder)
            bound = true
            pendingScriptPath?.let { runInternal(it) }
            pendingScriptPath = null
        }

        override fun onServiceDisconnected(name: ComponentName) {
            // Happens after Stop kills the :pyexec process — expected,
            // not an error. Clear state so the next Run rebinds cleanly.
            serviceMessenger = null
            bound = false
        }
    }

    private var pendingScriptPath: String? = null

    fun run(scriptText: String, scriptDisplayName: String, listener: Listener) {
        this.listener = listener
        val tempFile = File(context.cacheDir, "run_${scriptDisplayName.substringBeforeLast('.')}.py")
        tempFile.writeText(scriptText)

        if (bound) {
            runInternal(tempFile.absolutePath)
        } else {
            pendingScriptPath = tempFile.absolutePath
            val intent = Intent(context, PythonExecutionService::class.java)
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun runInternal(scriptPath: String) {
        val msg = Message.obtain(null, ExecutionProtocol.MSG_RUN)
        msg.replyTo = clientMessenger
        msg.data.putString(ExecutionProtocol.KEY_SCRIPT_PATH, scriptPath)
        serviceMessenger?.send(msg)
    }

    fun sendStdinLine(line: String) {
        val msg = Message.obtain(null, ExecutionProtocol.MSG_STDIN_LINE)
        msg.data.putString(ExecutionProtocol.KEY_TEXT, line)
        serviceMessenger?.send(msg)
    }

    /**
     * Stop = kill the :pyexec process directly, rather than any graceful
     * in-process message. This is what makes Stop instant and
     * unconditional regardless of what the running script is doing
     * (tight loop, blocked I/O, anything) — per the process-isolation
     * architecture decided at the start of the project.
     */
    fun stop() {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val targetProcessName = "${context.packageName}:pyexec"
        val runningProcesses = am.runningAppProcesses ?: return
        for (info in runningProcesses) {
            if (info.processName == targetProcessName) {
                Process.killProcess(info.pid)
                break
            }
        }
        try {
            context.unbindService(connection)
        } catch (t: Throwable) { /* already unbound */ }
        bound = false
        serviceMessenger = null
    }

    fun teardown() {
        if (bound) {
            try {
                context.unbindService(connection)
            } catch (t: Throwable) { /* ignore */ }
        }
        bound = false
    }
}