package dev.terminal.app.ui

import android.content.Context
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.sync.Mutex
import org.eclipse.tm4e.core.grammar.IGrammar
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.IThemeSource
import org.eclipse.tm4e.core.registry.Registry

/** Only the TextMate parser is shared; the editor and its document cache belong to Compose. */
internal object ShellTextMate {
    private val registry = Registry()
    private val tokenization = Mutex()
    private var loaded = false

    @Synchronized
    private fun load(context: Context) {
        if (loaded) return
        val assets = context.applicationContext.assets
        registry.setTheme(IThemeSource.fromInputStream(assets.open("editor/shell-theme.json"),
            "shell-theme.json", null))
        registry.addGrammar(IGrammarSource.fromInputStream(assets.open("editor/shell-unix-bash.tmLanguage.json"),
            "shell-unix-bash.tmLanguage.json", null))
        loaded = true
    }

    fun grammar(context: Context): IGrammar {
        load(context)
        return requireNotNull(registry.grammarForScopeName("source.shell"))
    }

    fun highlighter(context: Context): ShellHighlighter {
        val grammar = grammar(context)
        val colors = registry.colorMap.map { color ->
            if (color == null) Color.Unspecified else Color(android.graphics.Color.parseColor(color))
        }
        return ShellHighlighter(grammar, colors, tokenization)
    }
}
