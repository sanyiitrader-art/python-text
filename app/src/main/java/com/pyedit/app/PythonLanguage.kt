package com.pyedit.app

import android.content.Context
import androidx.core.content.ContextCompat
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * TextMate-based syntax highlighting has been removed after five
 * consecutive runtime failures in that module's undocumented grammar-
 * registry API. This is now the ONLY color-scheme path (previously a
 * fallback) — confirmed working in your last two builds, so the editor
 * background/gutter/cursor/selection colors are guaranteed correct.
 *
 * Per-token syntax highlighting (keywords vs strings vs comments in
 * different colors) is intentionally NOT attempted in this file — that
 * requires either the TextMate module we just removed, or a hand-written
 * tokenizer wired through sora-editor's Language/AnalyzeManager SPI,
 * which is a separate, larger, equally-unverified piece of API I want to
 * tackle on its own once this baseline is confirmed stable on your device.
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
        ContextCompat.getColor(context, resId)
}