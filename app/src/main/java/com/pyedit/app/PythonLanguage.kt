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
                setColor(LINE_NUMBER_CURRENT, colorInt(context, R.color.accent_grey))
                setColor(SELECTED_TEXT_BACKGROUND, colorInt(context, R.color.selection_overlay))
                setColor(SELECTION_INSERT, colorInt(context, R.color.cursor_color))
                setColor(TEXT_NORMAL, colorInt(context, R.color.text_normal))

                // Syntax colors (see SyntaxHighlighting)
                setColor(KEYWORD, colorInt(context, R.color.mint_primary))
                setColor(COMMENT, colorInt(context, R.color.text_comment))
                setColor(LITERAL, colorInt(context, R.color.warning_color))      // numbers, true/false/None
                setColor(ATTRIBUTE_VALUE, 0xFFE8A87C.toInt())                    // strings
                setColor(FUNCTION_NAME, 0xFF7AA2F7.toInt())
                setColor(IDENTIFIER_NAME, 0xFF6FC3DF.toInt())                    // class names, CSS classes
                setColor(OPERATOR, 0xFF9AA9B2.toInt())
                setColor(HTML_TAG, 0xFFF07178.toInt())
                setColor(ATTRIBUTE_NAME, 0xFFC792EA.toInt())
                setColor(ANNOTATION, 0xFFC792EA.toInt())
            }
        }
        editor.colorScheme = scheme
    }

    private fun colorInt(context: Context, resId: Int): Int =
        ContextCompat.getColor(context, resId)
}