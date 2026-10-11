package dev.terminal.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import dev.terminal.app.ui.ShellSyntaxTransformation
import org.junit.Assert.*
import org.junit.Test

class ShellSyntaxTest {
    @Test fun distinguishesKeywordsCommentsAndLiteralHashes() {
        val text = "#!/bin/sh\nif true; then\n  echo foo#bar # comment\nfi"
        val tokens = shellTokens(text)
        assertEquals(listOf("#!/bin/sh", "# comment"), tokens.ofKind(text, ShellTokenKind.COMMENT))
        assertEquals(listOf("if", "then", "fi"), tokens.ofKind(text, ShellTokenKind.KEYWORD))
        assertEquals(listOf(";"), tokens.ofKind(text, ShellTokenKind.OPERATOR))
    }

    @Test fun quotesEscapesAndParametersKeepTheirShellMeaning() {
        val text = """NAME=value
echo '${'$'}HOME # literal' "${'$'}HOME ${'$'}{USER:-guest} \"" \${'$'}HOME ${'$'}# ${'$'}1"""
        val tokens = shellTokens(text)
        assertEquals(listOf("NAME", "\$HOME", "\${USER:-guest}", "\$#", "\$1"), tokens.ofKind(text, ShellTokenKind.VARIABLE))
        assertTrue(tokens.ofKind(text, ShellTokenKind.STRING).contains("'\$HOME # literal'"))
        assertTrue(tokens.ofKind(text, ShellTokenKind.COMMENT).isEmpty())
    }

    @Test fun substitutionsAndAnsiQuotesStayBounded() {
        val text = "echo \$(printf '%s' \"value )\") \$((1 + (2))) \$'it\\'s fine' `date`"
        val tokens = shellTokens(text)
        assertEquals(listOf("\$(printf '%s' \"value )\")", "\$((1 + (2)))", "`date`"), tokens.ofKind(text, ShellTokenKind.VARIABLE))
        assertEquals(listOf("\$'it\\'s fine'"), tokens.ofKind(text, ShellTokenKind.STRING))
        assertOrderedBounds(text, tokens)
    }

    @Test fun heredocsRespectQuotingTabStrippingAndMultipleDocuments() {
        val text = "cat <<'LITERAL' <<-EXPAND\n\$HOME # not a comment\nLITERAL\n\t\$HOME\n\tEXPAND\nif true; then echo done; fi"
        val tokens = shellTokens(text)
        assertEquals(listOf("\$HOME"), tokens.ofKind(text, ShellTokenKind.VARIABLE))
        assertTrue(tokens.ofKind(text, ShellTokenKind.COMMENT).isEmpty())
        assertEquals(listOf("if", "then", "done", "fi"), tokens.ofKind(text, ShellTokenKind.KEYWORD))
        assertOrderedBounds(text, tokens)
    }

    @Test fun incompleteAndUnicodeScriptsKeepValidOffsets() {
        for (text in listOf("echo 'unfinished", "echo \"한글🙂 \${HOME", "cat <<EOF\n\$USER # body", "echo \"literal \$ \"",
            "if " + "한글🙂 ".repeat(1300))) assertOrderedBounds(text, shellTokens(text))
    }

    @Test fun highlightingLeavesEveryOriginalCharacterAndOffsetUntouched() {
        val text = "#!/bin/sh\nif [ \"\$HOME\" ]; then\n\tprintf '한글🙂\\n'\nfi"
        val result = ShellSyntaxTransformation(Color.Blue, Color.Gray, Color.White).filter(AnnotatedString(text))
        assertEquals(text, result.text.text)
        assertTrue(result.text.spanStyles.map { it.item.color }.distinct().size >= 4)
        for (offset in 0..text.length) {
            assertEquals(offset, result.offsetMapping.originalToTransformed(offset))
            assertEquals(offset, result.offsetMapping.transformedToOriginal(offset))
        }
    }

    private fun List<ShellToken>.ofKind(text: String, kind: ShellTokenKind) = filter { it.kind == kind }.map { text.substring(it.start, it.end) }
    private fun assertOrderedBounds(text: String, tokens: List<ShellToken>) {
        tokens.forEach { assertTrue(it.start >= 0 && it.end > it.start && it.end <= text.length) }
        tokens.zipWithNext { first, second -> assertTrue(first.end <= second.start) }
    }
}
