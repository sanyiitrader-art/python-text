package com.pyedit.app

import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.pyedit.app.databinding.ActivityMainBinding
import java.util.Locale

class ExecutionUiController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val executionController: ExecutionController,
    private val getScriptText: () -> String,
    private val getFileName: () -> String,
    private val onJumpToError: (Int) -> Unit,
    private val onRunStateChanged: (isRunning: Boolean) -> Unit,
    private val isKeyboardVisible: () -> Boolean,
    private val onShowOutputPanel: () -> Unit,
    private val onError: (String, Throwable) -> Unit
) {
    var isRunning: Boolean = false
        private set

    private val maxOutputChars = 200_000
    private val trimToChars = 150_000

    // Item 8: real elapsed time + whether the script raised an error.
    private var runStartNanos = 0L
    private var hadError = false

    fun setup() {
        binding.outputPanel.tvOutputText.movementMethod = LinkMovementMethod.getInstance()
        binding.outputPanel.editStdin.setOnEditorActionListener { v, _, event ->
            if (event == null || event.keyCode == KeyEvent.KEYCODE_ENTER) {
                val line = v.text.toString()
                appendOutput("$line\n")
                executionController.sendStdinLine(line)
                v.setText("")
                setStdinActive(false)
                true
            } else {
                false
            }
        }

        executionController.reconnectIfRunning(buildListener()) { running ->
            activity.runOnUiThread {
                if (running) {
                    isRunning = true
                    runStartNanos = 0L // real start time is unknown after a relaunch
                    hadError = false
                    onRunStateChanged(true)
                    onShowOutputPanel()
                    appendOutput("\n[Reconnected — program is still running]\n")
                }
            }
        }
    }

    fun onRunStopClicked() {
        if (isRunning) {
            executionController.stop()
            isRunning = false
            onRunStateChanged(false)
            setStdinActive(false)
            appendStatusLine("\u25A0 you stopped execution", R.color.accent_grey)
        } else {
            val scriptText = getScriptText()
            val name = getFileName()
            binding.outputPanel.tvOutputText.text = ""
            onShowOutputPanel()
            isRunning = true
            hadError = false
            runStartNanos = System.nanoTime()
            onRunStateChanged(true)
            setStdinActive(false)
            appendHeaderLine("$ python $name\n")

            executionController.run(scriptText, name, buildListener())
        }
    }

    private fun buildListener(): ExecutionController.Listener = object : ExecutionController.Listener {
        override fun onStdout(text: String) = activity.runOnUiThread { appendOutput(text) }
        override fun onStderr(text: String) = activity.runOnUiThread { appendOutput(text) }
        override fun onInputRequested() = activity.runOnUiThread { setStdinActive(true) }
        override fun onExited() = activity.runOnUiThread {
            // Ignore a late exit signal if Stop already ended this run.
            if (isRunning) {
                isRunning = false
                onRunStateChanged(false)
                setStdinActive(false)
                appendFinishLine()
            }
        }
        override fun onError(line: Int, errorType: String, message: String) = activity.runOnUiThread {
            hadError = true
            appendErrorSummary(line, errorType, message)
        }
    }

    /** Item 8: closing line with the actual measured time. */
    private fun appendFinishLine() {
        val timePart = if (runStartNanos > 0L) {
            val seconds = (System.nanoTime() - runStartNanos) / 1_000_000_000.0
            " in " + String.format(Locale.US, "%.2f", seconds) + " s"
        } else ""

        if (hadError) {
            appendStatusLine("\u2717 Execution failed$timePart", R.color.error_color)
        } else {
            appendStatusLine("\u2713 Finished successfully$timePart", R.color.success_color)
        }
    }

    fun runCompileCheck() {
        val scriptText = getScriptText()
        val name = getFileName()

        try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(activity))
            }
            val py = Python.getInstance()
            val runner = py.getModule("pyedit_runner")
            val result = runner.callAttr("check_syntax", scriptText, name)
            val resultList = result.asList()
            val ok = resultList[0].toBoolean()

            binding.outputPanel.tvOutputText.text = ""
            onShowOutputPanel()
            appendHeaderLine("$ python -m py_compile $name\n")

            if (ok) {
                appendOutput("No syntax errors found.\n")
            } else {
                val errList = resultList[1].asList()
                val line = errList[0].toInt()
                val type = errList[1].toString()
                val message = errList[2].toString()
                appendErrorSummary(line, type, message)
            }
        } catch (t: Throwable) {
            onError("Compile check failed", t)
        }
    }

    private fun appendOutput(text: String) {
        binding.outputPanel.tvOutputText.append(text)
        trimOutputIfNeeded()
        scrollOutputToEnd()
    }

    private fun scrollOutputToEnd() {
        binding.outputPanel.outputScroll.post {
            binding.outputPanel.outputScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun trimOutputIfNeeded() {
        val current = binding.outputPanel.tvOutputText.text
        if (current.length <= maxOutputChars) return
        val cutFrom = current.length - trimToChars
        val newlineIndex = current.indexOf('\n', cutFrom)
        val safeCut = if (newlineIndex != -1) newlineIndex + 1 else cutFrom
        binding.outputPanel.tvOutputText.text = current.subSequence(safeCut, current.length)
    }

    /** Item 7: the italic "$ python <file>" line is grey now. */
    private fun appendHeaderLine(text: String) {
        val spannable = SpannableString(text)
        spannable.setSpan(StyleSpan(Typeface.ITALIC), 0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(
            ForegroundColorSpan(activity.getColor(R.color.accent_grey)),
            0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        binding.outputPanel.tvOutputText.append(spannable)
        trimOutputIfNeeded()
        scrollOutputToEnd()
    }

    /** Appends a coloured status line on its own line at the end of the
     * output (adds a line break first if the program's last output
     * didn't end with one). */
    private fun appendStatusLine(text: String, colorRes: Int) {
        val current = binding.outputPanel.tvOutputText.text
        val prefix = if (current.isNotEmpty() && current.last() != '\n') "\n" else ""
        val full = "$prefix$text\n"
        val span = SpannableString(full)
        span.setSpan(
            ForegroundColorSpan(activity.getColor(colorRes)),
            0, full.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        binding.outputPanel.tvOutputText.append(span)
        trimOutputIfNeeded()
        scrollOutputToEnd()
    }

    private fun appendErrorSummary(line: Int, errorType: String, message: String) {
        val summaryText = "\n$errorType: $message\n"
        val summarySpan = SpannableString(summaryText)
        summarySpan.setSpan(
            ForegroundColorSpan(activity.getColor(R.color.error_color)),
            0, summaryText.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        binding.outputPanel.tvOutputText.append(summarySpan)
        trimOutputIfNeeded()
        scrollOutputToEnd()

        if (line > 0) {
            onJumpToError(line)
        }
    }

    private fun setStdinActive(active: Boolean) {
        val editStdin = binding.outputPanel.editStdin
        editStdin.isEnabled = active
        editStdin.isFocusable = active
        editStdin.isFocusableInTouchMode = active
        editStdin.alpha = if (active) 1f else 0.4f

        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (active) {
            editStdin.post {
                editStdin.requestFocus()
                editStdin.setSelection(editStdin.text.length)
                if (!isKeyboardVisible()) {
                    imm.showSoftInput(editStdin, InputMethodManager.SHOW_FORCED)
                }
            }
        } else {
            editStdin.clearFocus()
            imm.hideSoftInputFromWindow(editStdin.windowToken, 0)
        }
    }
}