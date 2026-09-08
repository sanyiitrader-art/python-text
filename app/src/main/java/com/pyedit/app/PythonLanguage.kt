package com.pyedit.app

import android.content.Context
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.SymbolPairMatch

/**
 * Bootstraps Python editing behavior on a CodeEditor: TextMate-based
 * syntax highlighting (spec §40) using the bundled grammar/theme in
 * assets/textmate/, plus Python-aware smart indentation (§35-38) and
 * bracket/quote pairing with skip-over-existing-closer (§32-34).
 *
 * Grammar/theme registration is process-global and only needs to happen
 * once, so it's guarded by `registered`.
 */
object PythonLanguage {

    private var registered = false
    private const val GRAMMAR_SCOPE = "source.python"
    private const val GRAMMAR_PATH = "textmate/python.tmLanguage.json"
    private const val LANG_CONFIG_PATH = "textmate/language-configuration.json"
    private const val THEME_PATH = "textmate/pyedit-theme.json"
    private const val THEME_NAME = "pyedit-theme"

    fun attach(context: Context, editor: CodeEditor) {
        ensureRegistered(context)

        val language = TextMateLanguage.create(GRAMMAR_SCOPE, true)
        installSymbolPairs(language)
        installNewlineHandler(language)

        editor.setEditorLanguage(language)

        val themeModel = ThemeRegistry.getInstance().getTheme(THEME_NAME)
        val colorScheme = TextMateColorScheme.create(themeModel)
        editor.colorScheme = colorScheme
    }

    private fun ensureRegistered(context: Context) {
        if (registered) return

        FileProviderRegistry.getInstance().addFileProvider(
            AssetsFileResolver(context.assets)
        )

        GrammarRegistry.getInstance().loadGrammars(
            listOf(
                io.github.rosemoe.sora.langs.textmate.registry.model.GrammarDefinition(
                    GRAMMAR_PATH, GRAMMAR_SCOPE, LANG_CONFIG_PATH
                )
            )
        )

        ThemeRegistry.getInstance().loadTheme(
            ThemeModel(THEME_PATH, THEME_NAME)
        )

        registered = true
    }

    /**
     * Spec §32-34: typing an opener inserts the matching closer with the
     * cursor between them; typing a closer that's already sitting directly
     * under the cursor accepts it (moves past) instead of duplicating it.
     * sora-editor's SymbolPairMatch implements exactly this "accept
     * existing closer" behavior per pair.
     */
    private fun installSymbolPairs(language: TextMateLanguage) {
        val pairs = SymbolPairMatch()
        pairs.putPair('(', SymbolPairMatch.SymbolPair("(", ")"))
        pairs.putPair('[', SymbolPairMatch.SymbolPair("[", "]"))
        pairs.putPair('{', SymbolPairMatch.SymbolPair("{", "}"))
        pairs.putPair('"', SymbolPairMatch.SymbolPair("\"", "\""))
        pairs.putPair('\'', SymbolPairMatch.SymbolPair("'", "'"))
        language.symbolPairs = pairs
    }

    /**
     * Spec §35-38: Enter after a line ending in ':' indents one level;
     * indentation is preserved/continued otherwise; nested indentation
     * (§36) falls out naturally because each new block adds one more
     * level on top of the current line's existing indent, at any depth.
     *
     * Smart-backspace-removes-one-indent-level (§37) is handled by
     * sora-editor's built-in "delete empty line indent as unit" behavior,
     * enabled via the editor's indentation settings rather than here.
     */
    private fun installNewlineHandler(language: TextMateLanguage) {
        language.newlineHandlers = arrayOf(PythonColonNewlineHandler())
    }

    private class PythonColonNewlineHandler : NewlineHandler {
        override fun matchesRequirement(
            text: CharSequence,
            line: Int,
            column: Int
        ): Boolean {
            val beforeCursor = text.subSequence(0, column).toString().trimEnd()
            return beforeCursor.endsWith(":")
        }

        override fun handleNewline(
            text: CharSequence,
            line: Int,
            column: Int,
            tabSize: Int
        ): NewlineHandler.HandleResult {
            val currentLine = text.toString()
            val currentIndent = currentLine.takeWhile { it == ' ' }
            val newIndent = currentIndent + " ".repeat(tabSize)
            return NewlineHandler.HandleResult("\n$newIndent", 0)
        }
    }
}