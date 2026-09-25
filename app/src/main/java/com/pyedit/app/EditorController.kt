package com.pyedit.app

import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.pyedit.app.databinding.ActivityMainBinding
import com.pyedit.app.databinding.DialogEditorSettingsBinding
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentListener
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

class EditorController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val editorSettings: EditorSettings,
    private val onError: (String, Throwable) -> Unit
) {
    lateinit var editor: CodeEditor

    private var pythonEditingBehavior: PythonEditingBehavior? = null
    private var suppressContentCallback = false
    private var onUserEdit: (() -> Unit)? = null
    private var errorHighlightActive = false
    private var isKeyboardVisible = false

    fun setup() {
        editor = CodeEditor(activity)
        editor.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        binding.editorContainer.addView(editor)
        editor.isWordwrap = false

        try {
            PythonLanguage.attach(activity, editor)
        } catch (t: Throwable) {
            onError("Color scheme failed", t)
        }

        attachContentBehaviors()

        editor.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                clearErrorHighlightIfActive()
                hideKeyboard()
            }
            false
        }

        applyFontSize(editorSettings.fontSize)
        applyTabSize(editorSettings.tabSize)
        applyVisualPolish()
        setupPythonToolbar()
        setupKeyboardAwareToolbar()
    }

    private fun hideKeyboard() {
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(editor.windowToken, 0)
    }

    fun requestFocusAndShowKeyboard() {
        editor.requestFocus()
        editor.post {
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun onContentChanged(callback: () -> Unit) {
        onUserEdit = callback
    }

    fun getText(): String = editor.text.toString()

    fun loadText(content: String) {
        suppressContentCallback = true
        editor.setText(content)
        attachContentBehaviors()
        suppressContentCallback = false
    }

    /**
     * New for Step 7 (Replace All): unlike loadText(), this is a real
     * user-triggered edit and SHOULD mark the file dirty. Because
     * editor.setText() replaces the Content object entirely, a listener
     * added afterward can't retroactively see that one bulk change — so
     * onUserEdit is invoked manually right after, rather than relying on
     * the listener to catch it.
     */
    fun replaceAllText(newText: String) {
        suppressContentCallback = true
        editor.setText(newText)
        attachContentBehaviors()
        suppressContentCallback = false
        onUserEdit?.invoke()
    }

    private fun attachContentBehaviors() {
        try {
            val behavior = PythonEditingBehavior(editor)
            behavior.attach()
            pythonEditingBehavior = behavior
        } catch (t: Throwable) {
            onError("Smart editing setup failed", t)
        }

        editor.text.addContentListener(object : ContentListener {
            override fun beforeReplace(content: Content) {}
            override fun afterInsert(
                content: Content, startLine: Int, startColumn: Int,
                endLine: Int, endColumn: Int, insertedContent: CharSequence
            ) = notifyEdit()
            override fun afterDelete(
                content: Content, startLine: Int, startColumn: Int,
                endLine: Int, endColumn: Int, deletedContent: CharSequence
            ) = notifyEdit()
        })
    }

    private fun notifyEdit() {
        if (suppressContentCallback) return
        onUserEdit?.invoke()
    }

    fun jumpToLine(oneBasedLine: Int) {
        val zeroBasedLine = (oneBasedLine - 1).coerceIn(0, maxOf(0, editor.text.lineCount - 1))
        highlightErrorLine(zeroBasedLine)
    }

    /** New for Go to Line: plain cursor move, no red error highlight —
     * jumpToLine() above is specifically for error navigation. */
    fun moveCursorToLine(oneBasedLine: Int) {
        val zeroBasedLine = (oneBasedLine - 1).coerceIn(0, maxOf(0, editor.text.lineCount - 1))
        try {
            editor.setSelection(zeroBasedLine, 0)
        } catch (t: Throwable) { /* best-effort */ }
    }

    private fun highlightErrorLine(zeroBasedLine: Int) {
        try {
            editor.colorScheme.setColor(
                EditorColorScheme.SELECTED_TEXT_BACKGROUND,
                activity.getColor(R.color.error_highlight_overlay)
            )
            val lineLength = editor.text.getLineString(zeroBasedLine).length
            editor.setSelectionRegion(zeroBasedLine, 0, zeroBasedLine, lineLength)
            errorHighlightActive = true
        } catch (t: Throwable) {
            try {
                editor.setSelection(zeroBasedLine, 0)
            } catch (t2: Throwable) { /* best-effort */ }
        }
    }

    fun clearErrorHighlightIfActive() {
        if (!errorHighlightActive) return
        errorHighlightActive = false
        try {
            editor.colorScheme.setColor(
                EditorColorScheme.SELECTED_TEXT_BACKGROUND,
                activity.getColor(R.color.selection_overlay)
            )
        } catch (t: Throwable) { /* best-effort */ }
    }

    fun showEditorSettingsDialog() {
        val dialogBinding = DialogEditorSettingsBinding.inflate(activity.layoutInflater)

        dialogBinding.seekFontSize.progress = (editorSettings.fontSize - 10f).toInt()
        dialogBinding.seekTabSize.progress = editorSettings.tabSize - 2

        dialogBinding.seekFontSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val newSize = (10 + progress).toFloat()
                editorSettings.fontSize = newSize
                applyFontSize(newSize)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        dialogBinding.seekTabSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val newTabSize = 2 + progress
                editorSettings.tabSize = newTabSize
                applyTabSize(newTabSize)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        AlertDialog.Builder(activity)
            .setTitle("Editor Settings")
            .setView(dialogBinding.root)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun applyFontSize(size: Float) {
        try { editor.setTextSize(size) } catch (t: Throwable) { onError("Font size change failed", t) }
    }

    private fun applyTabSize(size: Int) {
        try { editor.tabWidth = size } catch (t: Throwable) { onError("Tab size change failed", t) }
    }

    private fun applyVisualPolish() {
        try { editor.setCursorWidth(activity.resources.displayMetrics.density * 2.5f) } catch (t: Throwable) { }
    }

    private fun setupPythonToolbar() {
        val tb = binding.pythonToolbar
        val insert: (String) -> Unit = { s -> editor.commitText(s) }

        tb.tbUndo.setOnClickListener { if (editor.canUndo()) editor.undo() }
        tb.tbRedo.setOnClickListener { if (editor.canRedo()) editor.redo() }
        tb.tbIndent.setOnClickListener { insert("    ") }
        tb.tbOutdent.setOnClickListener { removeOneIndentLevel() }
        tb.tbParenOpen.setOnClickListener { insert("(") }
        tb.tbParenClose.setOnClickListener { insert(")") }
        tb.tbBracketOpen.setOnClickListener { insert("[") }
        tb.tbBracketClose.setOnClickListener { insert("]") }
        tb.tbBraceOpen.setOnClickListener { insert("{") }
        tb.tbBraceClose.setOnClickListener { insert("}") }
        tb.tbSquote.setOnClickListener { insert("'") }
        tb.tbDquote.setOnClickListener { insert("\"") }
        tb.tbColon.setOnClickListener { insert(":") }
        tb.tbUnderscore.setOnClickListener { insert("_") }
        tb.tbHash.setOnClickListener { insert("#") }
        tb.tbEq.setOnClickListener { insert("=") }
        tb.tbEqeq.setOnClickListener { insert("==") }
        tb.tbNeq.setOnClickListener { insert("!=") }
        tb.tbLt.setOnClickListener { insert("<") }
        tb.tbGt.setOnClickListener { insert(">") }
        tb.tbLe.setOnClickListener { insert("<=") }
        tb.tbGe.setOnClickListener { insert(">=") }
        tb.tbPlus.setOnClickListener { insert("+") }
        tb.tbMinus.setOnClickListener { insert("-") }
        tb.tbMul.setOnClickListener { insert("*") }
        tb.tbDiv.setOnClickListener { insert("/") }
        tb.tbFloordiv.setOnClickListener { insert("//") }
        tb.tbMod.setOnClickListener { insert("%") }
        tb.tbPow.setOnClickListener { insert("**") }
        tb.tbArrow.setOnClickListener { insert("->") }
        tb.tbAt.setOnClickListener { insert("@") }
        tb.tbEllipsis.setOnClickListener { insert("...") }
    }

    private fun removeOneIndentLevel() {
        val line = editor.cursor.leftLine
        val col = editor.cursor.leftColumn
        val lineText = editor.text.getLineString(line)
        val prefix = lineText.substring(0, col)
        val trailingSpaces = prefix.takeLastWhile { it == ' ' }.length
        val toRemove = minOf(trailingSpaces, 4)
        if (toRemove > 0) editor.text.delete(line, col - toRemove, line, col)
    }

    private fun setupKeyboardAwareToolbar() {
        val rootView = binding.root
        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            val visibleFrame = Rect()
            rootView.getWindowVisibleDisplayFrame(visibleFrame)
            val screenHeight = rootView.rootView.height
            if (screenHeight == 0) return@addOnGlobalLayoutListener
            val heightDiff = screenHeight - visibleFrame.bottom
            isKeyboardVisible = heightDiff > screenHeight * 0.15
            binding.pythonToolbar.root.visibility = if (isKeyboardVisible) View.VISIBLE else View.GONE
        }
    }

    fun isKeyboardCurrentlyVisible(): Boolean = isKeyboardVisible
}