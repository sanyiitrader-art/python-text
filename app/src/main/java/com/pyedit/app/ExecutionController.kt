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
        fun onInputRequested()
        fun onError(line: Int, errorType: String, message: String)
    }

    private data class PendingRun(
        val path: String,
        val language: String,
        val compilerPath: String?,
        val displayName: String
    )

    private var serviceMessenger: Messenger? = null
    private var listener: Listener? = null
    private var bound = false
    private var pendingReconnect = false
    private var pendingStatusCallback: ((Boolean) -> Unit)? = null
    private var pendingRun: PendingRun? = null
    private var lastTempFile: File? = null

    private val clientMessenger = Messenger(android.os.Handler(android.os.Looper.getMainLooper()) { msg ->
        when (msg.what) {
            ExecutionProtocol.MSG_STDOUT ->
                listener?.onStdout(msg.data.getString(ExecutionProtocol.KEY_TEXT) ?: "")
            ExecutionProtocol.MSG_STDERR ->
                listener?.onStderr(msg.data.getString(ExecutionProtocol.KEY_TEXT) ?: "")
            ExecutionProtocol.MSG_EXITED -> {
                deleteTemp()
                listener?.onExited()
            }
            ExecutionProtocol.MSG_INPUT_REQUESTED ->
                listener?.onInputRequested()
            ExecutionProtocol.MSG_ERROR -> {
                val line = msg.data.getInt(ExecutionProtocol.KEY_ERROR_LINE)
                val type = msg.data.getString(ExecutionProtocol.KEY_ERROR_TYPE) ?: "Error"
                val message = msg.data.getString(ExecutionProtocol.KEY_ERROR_MESSAGE) ?: ""
                listener?.onError(line, type, message)
            }
            ExecutionProtocol.MSG_STATUS_RESPONSE -> {
                val running = msg.data.getString(ExecutionProtocol.KEY_TEXT)?.toBoolean() ?: false
                pendingStatusCallback?.invoke(running)
                pendingStatusCallback = null
            }
        }
        true
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            serviceMessenger = Messenger(binder)
            bound = true
            pendingRun?.let { runInternal(it) }
            pendingRun = null
            if (pendingReconnect) {
                pendingReconnect = false
                sendRegister()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            serviceMessenger = null
            bound = false
        }
    }

    /**
     * The temp copy keeps the original extension (the user's file is never
     * touched) and is deleted when the run ends or is stopped.
     */
    fun run(
        scriptText: String,
        scriptDisplayName: String,
        language: String,
        compilerPath: String?,
        listener: Listener
    ) {
        this.listener = listener
        deleteTemp()
        val ext = Languages.extensionOf(scriptDisplayName).ifEmpty { "txt" }
        val base = scriptDisplayName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val tempFile = File(context.cacheDir, "run_$base.$ext")
        tempFile.writeText(scriptText)
        lastTempFile = tempFile

        val pending = PendingRun(tempFile.absolutePath, language, compilerPath, scriptDisplayName)
        if (bound) {
            runInternal(pending)
        } else {
            pendingRun = pending
            val intent = Intent(context, PythonExecutionService::class.java)
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun deleteTemp() {
        lastTempFile?.delete()
        lastTempFile = null
    }

    fun reconnectIfRunning(listener: Listener, onStatus: (running: Boolean) -> Unit) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val targetProcessName = "${context.packageName}:pyexec"
        val isProcessAlive = am.runningAppProcesses?.any { it.processName == targetProcessName } == true

        if (!isProcessAlive) {
            onStatus(false)
            return
        }

        this.listener = listener
        pendingStatusCallback = onStatus

        if (bound) {
            sendRegister()
        } else {
            pendingReconnect = true
            val intent = Intent(context, PythonExecutionService::class.java)
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun sendRegister() {
        val msg = Message.obtain(null, ExecutionProtocol.MSG_REGISTER_CLIENT)
        msg.replyTo = clientMessenger
        serviceMessenger?.send(msg)
    }

    fun sendStdinLine(line: String) {
        val msg = Message.obtain(null, ExecutionProtocol.MSG_STDIN_LINE)
        msg.data.putString(ExecutionProtocol.KEY_TEXT, line)
        serviceMessenger?.send(msg)
    }

    private fun runInternal(p: PendingRun) {
        val msg = Message.obtain(null, ExecutionProtocol.MSG_RUN)
        msg.replyTo = clientMessenger
        msg.data.putString(ExecutionProtocol.KEY_SCRIPT_PATH, p.path)
        msg.data.putString(ExecutionProtocol.KEY_LANGUAGE, p.language)
        msg.data.putString(ExecutionProtocol.KEY_COMPILER_PATH, p.compilerPath)
        msg.data.putString(ExecutionProtocol.KEY_FILE_NAME, p.displayName)
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
        deleteTemp()
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