package com.pyedit.app

import android.content.Context
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.CodeEditor
import org.eclipse.tm4e.core.registry.IThemeSource

object PythonLanguage {

    private var registered = false
    private const val GRAMMAR_SCOPE = "source.python"
    private const val GRAMMARS_MANIFEST = "textmate/grammars.json"
    private const val THEME_PATH = "textmate/pyedit-theme.json"
    private const val THEME_NAME = "pyedit-theme"

    // No change to logic here — the fix for the crash is entirely on the
    // MainActivity.kt side (catching what this throws). Unchanged file,
    // resent only so both files in this round match exactly.
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

        GrammarRegistry.getInstance().loadGrammars(GRAMMARS_MANIFEST)

        val themeSource = IThemeSource.fromInputStream(
            FileProviderRegistry.getInstance().tryGetInputStream(THEME_PATH),
            THEME_PATH,
            null
        )
        ThemeRegistry.getInstance().loadTheme(ThemeModel(themeSource, THEME_NAME))

        registered = true
    }
}