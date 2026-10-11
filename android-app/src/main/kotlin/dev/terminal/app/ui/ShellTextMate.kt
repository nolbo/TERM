package dev.terminal.app.ui

import android.content.Context
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.DefaultGrammarDefinition
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import org.eclipse.tm4e.core.grammar.IGrammar
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.IThemeSource

/** Application-scoped, bundled grammar data; each editor owns its analyzer and releases it. */
internal object ShellTextMate {
    private val themes = ThemeRegistry()
    private val grammars = GrammarRegistry(null)
    private var loaded = false

    @Synchronized
    private fun load(context: Context) {
        if (loaded) return
        val assets = context.applicationContext.assets
        FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(assets))
        themes.loadTheme(ThemeModel(IThemeSource.fromInputStream(assets.open("editor/shell-theme.json"),
            "shell-theme.json", null), "Droid shell").apply { isDark = true })
        grammars.setTheme(themes.currentThemeModel)
        grammars.loadGrammar(DefaultGrammarDefinition.withLanguageConfiguration(
            IGrammarSource.fromInputStream(assets.open("editor/shell-unix-bash.tmLanguage.json"),
                "shell-unix-bash.tmLanguage.json", null), "editor/shell-language-configuration.json", "Shell", "source.shell"))
        loaded = true
    }

    fun language(context: Context): TextMateLanguage {
        load(context)
        return TextMateLanguage.create("source.shell", grammars, themes, false).apply {
            setAutoCompleteEnabled(false)
            useTab(true)
        }
    }

    fun colorScheme(context: Context): TextMateColorScheme {
        load(context)
        return TextMateColorScheme(themes, themes.currentThemeModel)
    }

    fun grammar(context: Context): IGrammar {
        load(context)
        return requireNotNull(grammars.findGrammar("source.shell"))
    }
}
