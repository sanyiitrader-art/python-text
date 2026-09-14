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

class ExecutionController(private val context: Context) {

    interface Listener {
        fun onStdout(text: String)
        fun onStderr(text: String)
        fun onExited()
        /** Python's input() is now actually blocked waiting for a line. */
        fun onInputRequested()
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
            ExecutionProtocol.MSG_INPUT_REQUESTED ->
                listener?.onInputRequested()
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