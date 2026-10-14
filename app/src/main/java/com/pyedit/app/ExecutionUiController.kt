package com.pyedit.app

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
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
    private val getCurrentDoc: () -> DocumentFile?,
    private val onJumpToError: (Int) -> Unit,
    private val onRunStateChanged: (isRunning: Boolean) -> Unit,
    private val isKeyboardVisible: () -> Boolean,
    private val onShowOutputPanel: () -> Unit,
    private val onError: (String, Throwable) -> Unit
) {
    var isRunning: Boolean = false
        private set

    private enum class JsMode(val label: String) {
        AUTO("Auto (detect)"), SCRIPT("Script (QuickJS)"), BROWSER("Browser (WebView)")
    }

    private data class Decision(val browser: Boolean, val why: String?)

    private val prefs = activity.getSharedPreferences("pyedit_run", Context.MODE_PRIVATE)

    private val maxOutputChars = 200_000
    private val trimToChars = 150_000

    private var runStartNanos = 0L
    private var hadError = false
    private var runToken = 0              // invalidates an in-flight TypeScript transpile on Stop
    private var lastRunTranspiled = false // compiled-JS line numbers aren't editor lines

    private val browserNotDefined = Regex(
        "\\b(document|window|navigator|localStorage|sessionStorage|alert|setTimeout|setInterval|fetch)\\b is not defined"
    )

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
                    runStartNanos = 0L
                    hadError = false
                    onRunStateChanged(true)
                    onShowOutputPanel()
                    appendOutput("\n[Reconnected — program is still running]\n")
                }
            }
        }
    }

    // --- JS/TS mode override (long-press Run) -----------------------------

    private fun loadMode(): JsMode =
        JsMode.values().firstOrNull { it.name == prefs.getString("js_mode", "AUTO") } ?: JsMode.AUTO

    fun showModeDialog() {
        val modes = JsMode.values()
        AlertDialog.Builder(activity)
            .setTitle("JavaScript / TypeScript mode")
            .setSingleChoiceItems(modes.map { it.label }.toTypedArray(), loadMode().ordinal) { d, which ->
                prefs.edit().putString("js_mode", modes[which].name).apply()
                Toast.makeText(activity, "Mode: ${modes[which].label}", Toast.LENGTH_SHORT).show()
                d.dismiss()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun decide(code: String): Decision = when (loadMode()) {
        JsMode.SCRIPT -> Decision(false, null)
        JsMode.BROWSER -> Decision(true, "browser mode selected")
        JsMode.AUTO -> JsEnvDetector.detect(code).let { Decision(it.browser, it.reason) }
    }

    // --- Run ---------------------------------------------------------------

    fun onRunStopClicked() {
        if (isRunning) {
            runToken++ // cancels a pending TypeScript transpile too
            executionController.stop()
            isRunning = false
            onRunStateChanged(false)
            setStdinActive(false)
            appendStatusLine("\u25A0 you stopped execution", R.color.accent_grey)
            return
        }

        val name = getFileName()
        when (Languages.forFileName(name)) {
            Lang.PYTHON -> startRun(name, "python", null, "$ python $name")
            Lang.LUA -> startRun(name, "lua", null, "$ lua $name")
            Lang.JAVASCRIPT -> runJavaScript(name)
            Lang.TYPESCRIPT -> {
                if (RuntimeInstaller.isTypeScriptUsable(activity)) {
                    runTypeScript(name)
                } else {
                    showMissingRuntime(
                        "TypeScript compiler not installed",
                        "Running .ts files needs the TypeScript compiler. Your file and its contents are untouched."
                    )
                }
            }
            Lang.WEB -> launchWebPreview(name)
            Lang.CSS -> Toast.makeText(
                activity,
                "A .css file is a stylesheet, not a program. Open the HTML file that uses it and press Run.",
                Toast.LENGTH_LONG
            ).show()
            Lang.RUBY -> showMissingRuntime(
                "Ruby isn't available",
                "No Ruby runtime that can run on Android from app storage is available, so .rb files can't be run."
            )
            Lang.PHP -> showMissingRuntime(
                "PHP isn't available",
                "No PHP runtime that can run on Android from app storage is available, so .php files can't be run."
            )
            null -> Toast.makeText(activity, "This file type can't be run or previewed.", Toast.LENGTH_LONG).show()
        }
    }

    private fun beginRun(header: String) {
        binding.outputPanel.tvOutputText.text = ""
        onShowOutputPanel()
        isRunning = true
        hadError = false
        runStartNanos = System.nanoTime()
        onRunStateChanged(true)
        setStdinActive(false)
        appendHeaderLine("$header\n")
    }

    private fun startRun(name: String, language: String, compilerPath: String?, header: String) {
        lastRunTranspiled = false
        beginRun(header)
        executionController.run(getScriptText(), name, language, compilerPath, buildListener())
    }

    private fun runJavaScript(name: String) {
        val code = getScriptText()
        val d = decide(code)
        if (d.browser) {
            binding.outputPanel.tvOutputText.text = ""
            onShowOutputPanel()
            appendHeaderLine("$ browser (WebView) $name\n")
            openInBrowser(name, code, d.why)
        } else {
            lastRunTranspiled = false
            beginRun("$ quickjs $name")
            executionController.run(code, name, "js", null, buildListener())
        }
    }

    /** Transpile (no type-check) off the UI thread, then pick the engine on the result. */
    private fun runTypeScript(name: String) {
        val text = getScriptText()
        val token = ++runToken
        beginRun("$ tsc (transpile only, no type-check) $name")
        val compiler = RuntimeInstaller.compilerFile(activity)

        Thread(null, {
            val result = try { TsTranspiler.transpile(compiler, text, name) } catch (t: Throwable) { null }
            activity.runOnUiThread {
                if (token != runToken) return@runOnUiThread // stopped meanwhile
                val js = result?.js
                if (js == null) {
                    val first = result?.errors?.firstOrNull()
                    hadError = true
                    appendErrorSummary(first?.first ?: 0, "SyntaxError", first?.second ?: "The TypeScript compiler failed")
                    isRunning = false
                    onRunStateChanged(false)
                    appendFinishLine()
                    return@runOnUiThread
                }
                val d = decide(js)
                if (d.browser) {
                    isRunning = false
                    onRunStateChanged(false)
                    openInBrowser(name, js, d.why)
                } else {
                    lastRunTranspiled = true
                    appendHeaderLine("$ quickjs (compiled from TypeScript) $name\n")
                    executionController.run(js, name, "js", null, buildListener())
                }
            }
        }, "ts-transpile", 32L * 1024 * 1024).start()
    }

    /** The script becomes the only <script> of a generated page (same first line, so
     * console line numbers match the code). Console output shows on the preview screen. */
    private fun openInBrowser(name: String, code: String, why: String?) {
        appendOutput("Opened in the browser preview (${why ?: "browser mode"}). Console output is shown at the bottom of that screen.\n")
        val safe = code.replace(Regex("</script", RegexOption.IGNORE_CASE), "<\\\\/script")
        WebPreviewHolder.html =
            "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head><body><script>$safe\n</script></body></html>"
        WebPreviewHolder.title = name
        WebPreviewHolder.dirUri = null
        activity.startActivity(Intent(activity, WebPreviewActivity::class.java))
    }

    private fun launchWebPreview(name: String) {
        val dir = getCurrentDoc()?.parentFile
        WebPreviewHolder.html = getScriptText()
        WebPreviewHolder.title = name
        WebPreviewHolder.dirUri = dir?.uri
        if (dir == null) {
            Toast.makeText(
                activity,
                "Opened without its folder - linked CSS/JS/image files can't load. Open the folder first.",
                Toast.LENGTH_LONG
            ).show()
        }
        activity.startActivity(Intent(activity, WebPreviewActivity::class.java))
    }

    private fun showMissingRuntime(title: String, message: String) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Install other languages") { _, _ ->
                activity.startActivity(Intent(activity, InstallLanguagesActivity::class.java))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun buildListener(): ExecutionController.Listener = object : ExecutionController.Listener {
        override fun onStdout(text: String) = activity.runOnUiThread { appendOutput(text) }
        override fun onStderr(text: String) = activity.runOnUiThread { appendOutput(text) }
        override fun onInputRequested() = activity.runOnUiThread { setStdinActive(true) }
        override fun onExited() = activity.runOnUiThread {
            if (isRunning) {
                isRunning = false
                onRunStateChanged(false)
                setStdinActive(false)
                appendFinishLine()
            }
        }
        override fun onError(line: Int, errorType: String, message: String) = activity.runOnUiThread {
            hadError = true
            if (lastRunTranspiled && line > 0) {
                // line refers to the compiled JS, not the .ts file: don't jump the editor
                appendErrorSummary(0, errorType, "$message (compiled JS line $line)")
            } else {
                appendErrorSummary(line, errorType, message)
            }
            // Never retry silently in another engine - just explain and point to the override.
            if (browserNotDefined.containsMatchIn(message)) {
                appendStatusLine(
                    "\u2139 This code needs a browser. Long-press Run to switch the mode to Browser.",
                    R.color.warning_color
                )
            }
        }
    }

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
        val name = getFileName()
        if (Languages.forFileName(name) != Lang.PYTHON) {
            Toast.makeText(activity, "Compile check is only available for Python files.", Toast.LENGTH_LONG).show()
            return
        }
        val scriptText = getScriptText()

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