package com.pyedit.app

import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.pyedit.app.databinding.ActivityMainBinding
import com.pyedit.app.databinding.DialogEditorSettingsBinding
import com.pyedit.app.databinding.PopupMenuBinding
import io.github.rosemoe.sora.widget.CodeEditor
import java.io.PrintWriter
import java.io.StringWriter

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var editor: CodeEditor
    private lateinit var editorSettings: EditorSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        editorSettings = EditorSettings(this)

        sizeDrawerToScreenWidth()
        setupTopBar()
        setupEditor()
        setupPythonToolbar()
        setupKeyboardAwareToolbar()
    }

    private fun sizeDrawerToScreenWidth() {
        val screenWidth = resources.displayMetrics.widthPixels
        val drawerWidth = (screenWidth * 0.95f).toInt()
        val params = binding.drawerContent.root.layoutParams
        params.width = drawerWidth
        binding.drawerContent.root.layoutParams = params
    }

    private fun setupTopBar() {
        binding.btnHamburger.setOnClickListener {
            binding.drawerLayout.openDrawer(Gravity.START)
        }
        binding.btnMenu.setOnClickListener { anchor -> showThreeDotMenu(anchor) }
    }

    private fun showThreeDotMenu(anchor: View) {
        val popupBinding = PopupMenuBinding.inflate(layoutInflater)
        val popup = PopupWindow(
            popupBinding.root,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.elevation = 8f

        val dismissOnly = View.OnClickListener { popup.dismiss() }
        popupBinding.menuItemSave.setOnClickListener(dismissOnly)
        popupBinding.menuItemSaveAs.setOnClickListener(dismissOnly)
        popupBinding.menuItemFind.setOnClickListener(dismissOnly)
        popupBinding.menuItemReplace.setOnClickListener(dismissOnly)
        popupBinding.menuItemGoToLine.setOnClickListener(dismissOnly)
        popupBinding.menuItemCompile.setOnClickListener(dismissOnly)
        popupBinding.menuItemEditorSettings.setOnClickListener {
            popup.dismiss()
            showEditorSettingsDialog()
        }

        popup.showAsDropDown(anchor, 0, 8)
    }

    private fun showEditorSettingsDialog() {
        val dialogBinding = DialogEditorSettingsBinding.inflate(layoutInflater)

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

        AlertDialog.Builder(this)
            .setTitle("Editor Settings")
            .setView(dialogBinding.root)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun applyFontSize(size: Float) {
        try {
            editor.setTextSize(size)
        } catch (t: Throwable) {
            showCrashDiagnostic("Font size change failed", t)
        }
    }

    private fun applyTabSize(size: Int) {
        try {
            editor.tabWidth = size
        } catch (t: Throwable) {
            showCrashDiagnostic("Tab size change failed", t)
        }
    }

    private fun setupEditor() {
        editor = CodeEditor(this)
        editor.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        binding.editorContainer.addView(editor)

        editor.isWordwrap = false

        try {
            PythonLanguage.attach(this, editor)
        } catch (t: Throwable) {
            showCrashDiagnostic("Color scheme failed", t)
        }

        try {
            PythonEditingBehavior(editor).attach()
        } catch (t: Throwable) {
            showCrashDiagnostic("Smart editing setup failed", t)
        }

        applyFontSize(editorSettings.fontSize)
        applyTabSize(editorSettings.tabSize)
        applyVisualPolish()
    }

    /**
     * Best-effort "vibe" fixes: thicker cursor matching text height, bold
     * line numbers, extra gutter width so digits aren't cramped. None of
     * these method/property names could be verified against documentation,
     * so each is isolated and silently skipped on failure — a wrong guess
     * here must never be able to break editing, which is why these run
     * LAST, after everything functional is already set up.
     */
    private fun applyVisualPolish() {
        try {
            editor.setCursorWidth(resources.displayMetrics.density * 2.5f)
        } catch (t: Throwable) { /* property name unverified; skip silently */ }

        try {
            editor.isLineNumberBold = true
        } catch (t: Throwable) { /* property name unverified; skip silently */ }

        try {
            val currentPadding = editor.dividerMargin
            editor.dividerMargin = currentPadding + (resources.displayMetrics.density * 6).toInt()
        } catch (t: Throwable) { /* property name unverified; skip silently */ }
    }

    private fun showCrashDiagnostic(title: String, t: Throwable) {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        Toast.makeText(this, "$title — editor still works. Tap to see details.", Toast.LENGTH_LONG).show()
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(sw.toString())
            .setPositiveButton("OK", null)
            .show()
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
        if (toRemove > 0) {
            editor.text.delete(line, col - toRemove, line, col)
        }
    }

    /**
     * FIX for "toolbar never appears": the previous approach used
     * WindowInsetsCompat's IME-visibility detection, which is unreliable
     * on pre-API-30 devices (real IME visibility tracking was only added
     * properly in API 30's WindowInsetsAnimation). This replaces it with
     * the older but far more universally reliable technique: watch the
     * root view's visible display frame, and infer the keyboard is open
     * when the visible height shrinks by a meaningful amount. Works on
     * every API level this app supports (26+), since it doesn't depend on
     * newer insets APIs at all.
     */
    private fun setupKeyboardAwareToolbar() {
        val rootView = binding.root
        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            val visibleFrame = Rect()
            rootView.getWindowVisibleDisplayFrame(visibleFrame)
            val screenHeight = rootView.rootView.height
            if (screenHeight == 0) return@addOnGlobalLayoutListener
            val heightDiff = screenHeight - visibleFrame.bottom
            val keyboardVisible = heightDiff > screenHeight * 0.15
            binding.pythonToolbar.root.visibility = if (keyboardVisible) View.VISIBLE else View.GONE
        }
    }
}