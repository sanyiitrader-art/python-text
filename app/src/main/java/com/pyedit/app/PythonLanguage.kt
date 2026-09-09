package com.pyedit.app

import android.content.Context
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.GrammarDefinition
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.CodeEditor
import org.eclipse.tm4e.core.registry.IThemeSource

/**
 * Syntax highlighting (spec §40) via the bundled TextMate grammar/theme.
 * Bracket/quote pairing (§32-34) and Python-aware indent/dedent (§35-38)
 * are NOT written here as custom Kotlin — they're declared once in
 * assets/textmate/language-configuration.json (autoClosingPairs,
 * indentationRules) and TextMateLanguage applies them automatically once
 * the grammar is registered with that config file attached.
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
        editor.setEditorLanguage(language)
        editor.colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
    }

    private fun ensureRegistered(context: Context) {
        if (registered) return

        FileProviderRegistry.getInstance().addFileProvider(
            AssetsFileResolver(context.assets)
        )

        GrammarRegistry.getInstance().loadGrammars(
            listOf(
                GrammarDefinition.withLanguageConfiguration(
                    GRAMMAR_PATH,
                    LANG_CONFIG_PATH,
                    GRAMMAR_SCOPE
                )
            )
        )

        val themeSource = IThemeSource.fromInputStream(
            FileProviderRegistry.getInstance().tryGetInputStream(THEME_PATH),
            THEME_PATH,
            null
        )
        ThemeRegistry.getInstance().loadTheme(ThemeModel(themeSource, THEME_NAME))

        registered = true
    }
}