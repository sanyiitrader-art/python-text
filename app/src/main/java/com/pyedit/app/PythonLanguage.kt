package com.pyedit.app

import android.content.Context
import androidx.core.content.ContextCompat
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

object PythonLanguage {

    fun attach(context: Context, editor: CodeEditor) {
        val scheme = object : EditorColorScheme() {
            init {
                setColor(WHOLE_BACKGROUND, colorInt(context, R.color.editor_background))
                setColor(LINE_NUMBER_BACKGROUND, colorInt(context, R.color.gutter_background))
                setColor(LINE_NUMBER, colorInt(context, R.color.text_comment))
                // Item 7: current-line number is grey instead of mint
                setColor(LINE_NUMBER_CURRENT, colorInt(context, R.color.accent_grey))
                setColor(SELECTED_TEXT_BACKGROUND, colorInt(context, R.color.selection_overlay))
                // cursor_color is grey now (colors.xml)
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
        ContextCompat.getColor(context, resId)
}