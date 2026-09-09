package com.pyedit.app

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.pyedit.app.databinding.ActivityMainBinding
import com.pyedit.app.databinding.PopupMenuBinding
import io.github.rosemoe.sora.widget.CodeEditor
import java.io.PrintWriter
import java.io.StringWriter

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var editor: CodeEditor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sizeDrawerToScreenWidth()
        setupTopBar()
        setupEditor()
        setupPythonToolbar()
        setupKeyboardAwareInsets()
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
        popupBinding.menuItemEditorSettings.setOnClickListener(dismissOnly)

        popup.showAsDropDown(anchor, 0, 8)
    }

    private fun setupEditor() {
        editor = CodeEditor(this)
        editor.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        binding.editorContainer.addView(editor)

        editor.isWordwrap = false

        // TextMate grammar/theme attachment is an enhancement, not core
        // editor functionality — a failure here must NOT crash the whole
        // app. Catching Throwable (not just Exception) deliberately, since
        // library init issues can surface as Error subclasses too. On
        // failure, the editor still works (Phase 1's other 90% of
        // requirements are unaffected), and we surface the real exception
        // on-screen since there's no ADB/logcat access available to debug
        // this remotely otherwise.
        try {
            PythonLanguage.attach(this, editor)
        } catch (t: Throwable) {
            showCrashDiagnostic(t)
        }
    }

    private fun showCrashDiagnostic(t: Throwable) {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        val fullTrace = sw.toString()

        Toast.makeText(
            this,
            "Syntax highlighting failed to load — editor still works. Tap to see details.",
            Toast.LENGTH_LONG
        ).show()

        AlertDialog.Builder(this)
            .setTitle("TextMate attach failed")
            .setMessage(fullTrace)
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

    private fun setupKeyboardAwareInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val keyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            binding.pythonToolbar.root.visibility =
                if (keyboardVisible) View.VISIBLE else View.GONE
            insets
        }
    }
}