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
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.pyedit.app.databinding.ActivityMainBinding
import com.pyedit.app.databinding.PopupMenuBinding
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
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

        // Safety net: apply our dark palette as the baseline FIRST, before
        // attempting TextMate. This is what was missing last round — if
        // TextMate throws, sora-editor's own default (light/white) scheme
        // was left in place instead. Now a TextMate failure can only mean
        // "no syntax colors", never "unreadable white editor".
        applyFallbackColorScheme()

        try {
            PythonLanguage.attach(this, editor)
        } catch (t: Throwable) {
            showCrashDiagnostic(t)
        }
    }

    private fun applyFallbackColorScheme() {
        val scheme = object : EditorColorScheme() {
            init {
                setColor(WHOLE_BACKGROUND, colorInt(R.color.editor_background))
                setColor(LINE_NUMBER_BACKGROUND, colorInt(R.color.gutter_background))
                setColor(LINE_NUMBER, colorInt(R.color.text_comment))
                setColor(LINE_NUMBER_CURRENT, colorInt(R.color.mint_primary))
                setColor(SELECTED_TEXT_BACKGROUND, colorInt(R.color.selection_overlay))
                setColor(SELECTION_INSERT, colorInt(R.color.cursor_color))
                setColor(TEXT_NORMAL, colorInt(R.color.text_normal))
                setColor(COMMENT, colorInt(R.color.text_comment))
                setColor(KEYWORD, colorInt(R.color.mint_primary))
                setColor(LITERAL, colorInt(R.color.warning_color))
            }
        }
        editor.colorScheme = scheme
    }

    private fun colorInt(resId: Int): Int = ContextCompat.getColor(this, resId)

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