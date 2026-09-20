package dev.terminal.runtime.session

/** Prompt-end notification for the terminal's line-at-a-time paste queue. */
internal fun bashPromptCommand(previous: String? = null): String {
    val integration = """
        case "${'$'}PS1" in *'\[\e]133;B\a\]') ;; *) PS1+='\[\e]133;B\a\]' ;; esac
        case "${'$'}PS2" in *'\[\e]133;B\a\]') ;; *) PS2='> \[\e]133;B\a\]' ;; esac
    """.trimIndent()
    return if (previous.isNullOrBlank()) integration else "$previous\n$integration"
}
