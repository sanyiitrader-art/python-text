package com.pyedit.app

import android.content.Context
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * TEMPORARY for Phase 1's compile gate: applies a manual color scheme
 * using the stable, documented EditorColorScheme public API instead of
 * sora-editor's TextMate grammar-registry bootstrap, whose exact
 * constructor/factory signatures for this version I could not verify
 * from documentation and got wrong three times in a row — not worth
 * further guessing against your CI runs.
 *
 * Full TextMate-based Python syntax highlighting (spec §40) is
 * re-added as its own isolated, testable step immediately after this
 * compiles — nothing about this is a permanent scope cut.
 */
object PythonLanguage {

    fun attach(context: Context, editor: CodeEditor) {
        val scheme = object : EditorColorScheme() {
            init {
                setColor(WHOLE_BACKGROUND, colorInt(context, R.color.editor_background))
                setColor(LINE_NUMBER_BACKGROUND, colorInt(context, R.color.gutter_background))
                setColor(LINE_NUMBER, colorInt(context, R.color.text_comment))
                setColor(LINE_NUMBER_CURRENT, colorInt(context, R.color.mint_primary))
                setColor(SELECTED_TEXT_BACKGROUND, colorInt(context, R.color.selection_overlay))
                setColor(SELECTION_INSERT, colorInt(context, R.color.cursor_color))
                setColor(TEXT_NORMAL, colorInt(context, R.color.text_normal))
                setColor(COMMENT, colorInt(context, R.color.text_comment))
                setColor(KEYWORD, colorInt(context, R.color.mint_primary))
                setColor(LITERAL, colorInt(context, R.color.warning_color))
            }
        }
        editor.colorScheme = scheme
    }

    private fun colorInt(context: Context, resId: Int): Int =
        androidx.core.content.ContextCompat.getColor(context, resId)
}