package dev.terminal.app

internal enum class ShellTokenKind { KEYWORD, STRING, COMMENT, VARIABLE, OPERATOR }
internal data class ShellToken(val start: Int, val end: Int, val kind: ShellTokenKind)

/** A forgiving lexer for display only: incomplete scripts remain editable and are never evaluated. */
internal fun shellTokens(text: String): List<ShellToken> = ShellLexer(text).scan()

private class ShellLexer(private val text: String) {
    private val tokens = mutableListOf<ShellToken>()
    private val heredocs = ArrayDeque<HereDoc>()
    private var index = 0
    private data class HereDoc(val delimiter: String, val stripTabs: Boolean, val expand: Boolean)

    fun scan(): List<ShellToken> {
        while (index < text.length) {
            val start = index
            when (val char = text[index]) {
                '\n' -> { index++; readHereDocs() }
                ' ', '\t', '\r' -> index++
                '\\' -> index = (index + 2).coerceAtMost(text.length)
                '#' -> if (wordBoundary(start)) {
                    index = text.indexOf('\n', index).takeIf { it >= 0 } ?: text.length
                    add(start, index, ShellTokenKind.COMMENT)
                } else readWord()
                '\'', '"' -> readQuote(char)
                '`' -> { index = quotedEnd(index, '`', text.length); add(start, index, ShellTokenKind.VARIABLE) }
                '$' -> if (text.getOrNull(index + 1) == '\'') readQuote('\'', prefix = 1, escapes = true)
                    else if (!readVariable(text.length)) index++
                in OPERATORS -> {
                    val operator = MULTI_OPERATORS.firstOrNull { text.startsWith(it, index) } ?: char.toString()
                    index += operator.length
                    add(start, index, ShellTokenKind.OPERATOR)
                    if (operator == "<<" || operator == "<<-") readHereDocDelimiter(operator == "<<-")
                }
                else -> readWord()
            }
        }
        return tokens
    }

    private fun readWord() {
        val start = index++
        while (index < text.length && !text[index].isWhitespace() && text[index] !in WORD_END) index++
        val word = text.substring(start, index)
        if (!wordBoundary(start)) return
        if (word in KEYWORDS) add(start, index, ShellTokenKind.KEYWORD)
        else {
            val assignment = word.indexOf('=')
            if (assignment > 0 && word.take(assignment).let { name ->
                    name.first().isNameStart() && name.all { it.isNamePart() }
                }) add(start, start + assignment, ShellTokenKind.VARIABLE)
        }
    }

    private fun readQuote(quote: Char, prefix: Int = 0, escapes: Boolean = quote == '"') {
        var segment = index
        index += prefix + 1
        while (index < text.length) {
            when {
                escapes && text[index] == '\\' -> index = (index + 2).coerceAtMost(text.length)
                text[index] == quote -> { index++; break }
                quote == '"' && text[index] == '$' -> {
                    add(segment, index, ShellTokenKind.STRING)
                    if (!readVariable(text.length)) { add(index, index + 1, ShellTokenKind.STRING); index++ }
                    segment = index
                }
                else -> index++
            }
        }
        add(segment, index, ShellTokenKind.STRING)
    }

    private fun readVariable(limit: Int): Boolean {
        val start = index
        if (index + 1 >= limit) return false
        val next = text.getOrNull(index + 1) ?: return false
        when {
            next.isNameStart() -> {
                index += 2
                while (index < limit && text[index].isNamePart()) index++
            }
            next in "@*#?$!-0123456789" -> index += 2
            next == '{' || next == '(' -> {
                val close = if (next == '{') '}' else ')'
                var depth = 1
                index += 2
                while (index < limit && depth > 0) {
                    when (text[index]) {
                        '\\' -> index = (index + 2).coerceAtMost(limit)
                        '\'', '"', '`' -> index = quotedEnd(index, text[index], limit)
                        next -> { depth++; index++ }
                        close -> { depth--; index++ }
                        else -> index++
                    }
                }
            }
            else -> return false
        }
        add(start, index, ShellTokenKind.VARIABLE)
        return true
    }

    private fun quotedEnd(start: Int, quote: Char, limit: Int): Int {
        var end = start + 1
        while (end < limit) {
            when {
                text[end] == '\\' && quote != '\'' -> end = (end + 2).coerceAtMost(limit)
                text[end++] == quote -> break
            }
        }
        return end
    }

    private fun readHereDocDelimiter(stripTabs: Boolean) {
        while (text.getOrNull(index) == ' ' || text.getOrNull(index) == '\t') index++
        val start = index
        val delimiter = StringBuilder()
        var quoted = false
        while (index < text.length && !text[index].isWhitespace() && text[index] !in OPERATORS) {
            when (val char = text[index]) {
                '\'', '"' -> {
                    quoted = true
                    val end = quotedEnd(index, char, text.length)
                    val contentEnd = if (end > index + 1 && text[end - 1] == char) end - 1 else end
                    delimiter.append(text, index + 1, contentEnd)
                    index = end
                }
                '\\' -> {
                    quoted = true
                    index++
                    if (index < text.length) delimiter.append(text[index++])
                }
                else -> { delimiter.append(char); index++ }
            }
        }
        if (index > start && delimiter.isNotEmpty()) {
            add(start, index, ShellTokenKind.STRING)
            heredocs.addLast(HereDoc(delimiter.toString(), stripTabs, !quoted))
        }
    }

    private fun readHereDocs() {
        while (heredocs.isNotEmpty() && index < text.length) {
            val doc = heredocs.removeFirst()
            val bodyStart = index
            var closingStart = text.length
            var closingEnd = text.length
            while (index < text.length) {
                val end = text.indexOf('\n', index).takeIf { it >= 0 } ?: text.length
                val line = text.substring(index, end).let { if (doc.stripTabs) it.trimStart('\t') else it }
                if (line == doc.delimiter) { closingStart = index; closingEnd = end; break }
                index = (end + 1).coerceAtMost(text.length)
            }
            index = bodyStart
            var segment = index
            if (doc.expand) while (index < closingStart) {
                when (text[index]) {
                    '\\' -> index = (index + 2).coerceAtMost(closingStart)
                    '$' -> {
                        add(segment, index, ShellTokenKind.STRING)
                        if (!readVariable(closingStart)) { add(index, index + 1, ShellTokenKind.STRING); index++ }
                        segment = index
                    }
                    else -> index++
                }
            }
            add(segment, closingStart, ShellTokenKind.STRING)
            add(closingStart, closingEnd, ShellTokenKind.STRING)
            index = (closingEnd + 1).coerceAtMost(text.length)
        }
    }

    private fun wordBoundary(position: Int) = position == 0 || text[position - 1].isWhitespace() || text[position - 1] in OPERATORS
    private fun add(start: Int, end: Int, kind: ShellTokenKind) { if (end > start) tokens += ShellToken(start, end, kind) }
    private fun Char.isNameStart() = this == '_' || this in 'a'..'z' || this in 'A'..'Z'
    private fun Char.isNamePart() = isNameStart() || this in '0'..'9'

    companion object {
        private const val OPERATORS = "|&;()<>{}"
        private const val WORD_END = "|&;()<>{}'\"\\$`"
        private val MULTI_OPERATORS = listOf(";;&", "<<<", "<<-", "&&", "||", ">>", "<<", ";;", ";&", "<&", ">&", ">|")
        private val KEYWORDS = setOf("if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case", "in", "esac",
            "function", "select", "time", "coproc", "export", "readonly", "local", "declare", "typeset", "unset", "source", "return", "break", "continue")
    }
}
