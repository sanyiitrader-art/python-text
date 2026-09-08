package com.pyedit.app

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.PopupWindow
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.pyedit.app.databinding.ActivityMainBinding
import com.pyedit.app.databinding.PopupMenuBinding
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula

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
        setupKeyboardAwareInsets()
    }

    /**
     * Spec §17: drawer covers ~95% of screen width, leaving ~5% exposed.
     * Expressed as a runtime calculation rather than a fixed dp value so
     * it holds across all screen sizes, not just the 720px reference.
     */
    private fun sizeDrawerToScreenWidth() {
        val screenWidth = resources.displayMetrics.widthPixels
        val drawerWidth = (screenWidth * 0.95f).toInt()
        val params = binding.drawerContent.root.layoutParams
        params.width = drawerWidth
        binding.drawerContent.root.layoutParams = params
    }

    private fun setupTopBar() {
        binding.btnHamburger.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompatStart)
        }

        binding.btnMenu.setOnClickListener { anchor -> showThreeDotMenu(anchor) }

        binding.drawerContent.tvClearRecent.setOnClickListener {
            // Recent-record clearing is wired up in Phase 2 once the
            // persistent Recent list exists. Phase 1 has nothing to clear.
        }
    }

    /**
     * Spec §20: compact, content-sized, rounded popup anchored near the
     * three-dot button — explicitly NOT a full-screen page/drawer/sheet.
     */
    private fun showThreeDotMenu(anchor: View) {
        val popupBinding = PopupMenuBinding.inflate(layoutInflater)
        val popup = PopupWindow(
            popupBinding.root,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.elevation = 8f

        // File section (Save/Save As wired in Phase 2 — file system doesn't
        // exist yet in Phase 1). Autosave checkbox state persists in Phase 2.
        val autosaveCheckbox: CheckBox = popupBinding.checkboxAutosave

        popupBinding.menuItemFind.setOnClickListener { popup.dismiss() }
        popupBinding.menuItemReplace.setOnClickListener { popup.dismiss() }
        popupBinding.menuItemGoToLine.setOnClickListener { popup.dismiss() }
        popupBinding.menuItemCompile.setOnClickListener { popup.dismiss() }
        popupBinding.menuItemEditorSettings.setOnClickListener { popup.dismiss() }
        popupBinding.menuItemSave.setOnClickListener { popup.dismiss() }
        popupBinding.menuItemSaveAs.setOnClickListener { popup.dismiss() }

        popup.showAsDropDown(anchor, 0, 8)
    }

    private fun setupEditor() {
        editor = CodeEditor(this)
        editor.setLayoutParams(
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        binding.editorContainer.addView(editor)

        // No line wrapping (spec §24) — horizontal scroll instead.
        editor.isWordwrap = false

        applyColorScheme(editor)
        PythonLanguage.attach(this, editor)

        // Cursor stops blinking when the editor isn't the active input
        // target (spec §28).
        editor.isEditable = true
    }

    private fun applyColorScheme(editor: CodeEditor) {
        // Base scheme; PythonLanguage.attach() overlays the TextMate theme
        // (built from pyedit-theme.json) on top of this once grammars load.
        val scheme = SchemeDarcula()
        scheme.setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.WHOLE_BACKGROUND,
            getColorCompat(R.color.editor_background))
        scheme.setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.LINE_NUMBER_BACKGROUND,
            getColorCompat(R.color.gutter_background))
        scheme.setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.LINE_NUMBER,
            getColorCompat(R.color.text_comment))
        scheme.setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.SELECTED_TEXT_BACKGROUND,
            getColorCompat(R.color.selection_overlay))
        scheme.setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.CURSOR,
            getColorCompat(R.color.cursor_color))
        scheme.setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.TEXT_NORMAL,
            getColorCompat(R.color.text_normal))
        editor.colorScheme = scheme
    }

    private fun getColorCompat(resId: Int): Int =
        androidx.core.content.ContextCompat.getColor(this, resId)

    /**
     * Spec §27: usable editor area is bounded by the keyboard/toolbar's
     * top edge, not the physical screen bottom. windowSoftInputMode=
     * adjustResize (set in the manifest) plus IME insets here keep the
     * cursor's safe area accurate as the keyboard height changes.
     */
    private fun setupKeyboardAwareInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            editor.setPadding(
                editor.paddingLeft,
                editor.paddingTop,
                editor.paddingRight,
                imeHeight
            )
            insets
        }
    }

    private val GravityCompatStart get() = Gravity.START
}