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

    // Spec §75: avoid unbounded memory for indefinitely-running programs.
    // A tight print-loop can produce output faster than a human could
    // ever read anyway, so trimming old lines once the buffer gets large
    // costs nothing real while capping worst-case memory growth.
    private val maxOutputChars = 200_000
    private val trimToChars = 150_000

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
    }

    fun onRunStopClicked() {
        if (isRunning) {
            executionController.stop()
            isRunning = false
            onRunStateChanged(false)
            setStdinActive(false)
            appendOutput("\n[Stopped]\n")
        } else {
            val scriptText = getScriptText()
            val name = getFileName()
            binding.outputPanel.tvOutputText.text = ""
            onShowOutputPanel()
            isRunning = true
            onRunStateChanged(true)
            setStdinActive(false)
            appendHeaderLine("$ python $name\n")

            executionController.run(scriptText, name, object : ExecutionController.Listener {
                override fun onStdout(text: String) = activity.runOnUiThread { appendOutput(text) }
                override fun onStderr(text: String) = activity.runOnUiThread { appendOutput(text) }
                override fun onInputRequested() = activity.runOnUiThread { setStdinActive(true) }
                override fun onExited() = activity.runOnUiThread {
                    isRunning = false
                    onRunStateChanged(false)
                    setStdinActive(false)
                }
                override fun onError(line: Int, errorType: String, message: String) = activity.runOnUiThread {
                    appendErrorSummary(line, errorType, message)
                }
            })
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

    /**
     * New: bounds the output buffer per spec §75. When the visible text
     * exceeds maxOutputChars, the oldest content is trimmed down to
     * trimToChars — done via a full re-set of the text (simplest correct
     * approach) rather than a partial edit, since spans (header/error
     * coloring) need to be dropped consistently along with the trimmed
     * text, not left dangling on now-invalid ranges.
     */
    private fun appendOutput(text: String) {
        binding.outputPanel.tvOutputText.append(text)
        trimOutputIfNeeded()
        binding.outputPanel.outputScroll.post {
            binding.outputPanel.outputScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun trimOutputIfNeeded() {
        val current = binding.outputPanel.tvOutputText.text
        if (current.length <= maxOutputChars) return
        val cutFrom = current.length - trimToChars
        // Cut at the next newline after cutFrom so we don't split a line
        // in half, leaving a stray partial line at the top.
        val newlineIndex = current.indexOf('\n', cutFrom)
        val safeCut = if (newlineIndex != -1) newlineIndex + 1 else cutFrom
        binding.outputPanel.tvOutputText.text = current.subSequence(safeCut, current.length)
    }

    private fun appendHeaderLine(text: String) {
        val spannable = SpannableString(text)
        spannable.setSpan(StyleSpan(Typeface.ITALIC), 0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(
            ForegroundColorSpan(activity.getColor(R.color.mint_primary)),
            0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        binding.outputPanel.tvOutputText.append(spannable)
        trimOutputIfNeeded()
        binding.outputPanel.outputScroll.post {
            binding.outputPanel.outputScroll.fullScroll(View.FOCUS_DOWN)
        }
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
        binding.outputPanel.outputScroll.post {
            binding.outputPanel.outputScroll.fullScroll(View.FOCUS_DOWN)
        }

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