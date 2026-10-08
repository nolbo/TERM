package dev.terminal.runtime.notifications

/** Shell-owned execution boundaries. User rc files may override these standard Bash hooks. */
internal fun shellActivityEnvironment(shell: String, environment: Map<String, String>): Map<String, String> {
    if (shell.substringAfterLast('/') != "bash") return environment
    val completed = "__terminal_status=\$?; printf '\\033]133;D;%s\\007' \"\$__terminal_status\"; (exit \"\$__terminal_status\")"
    val prompt = "__terminal_prompt_status=\$?; printf '\\033]133;A\\007'; (exit \"\$__terminal_prompt_status\")"
    val integrated = environment + mapOf(
        "PS0" to ("\u001b]133;C\u0007" + environment["PS0"].orEmpty()),
        "PROMPT_COMMAND" to (completed + environment["PROMPT_COMMAND"]?.takeIf { it.isNotBlank() }?.let { "; $it" }.orEmpty() + "; $prompt"),
    )
    // Reserve one entry for the argv envelope; hooks must not make a valid AVF launch fail.
    return if (integrated.size <= 63 && integrated.values.all { it.toByteArray().size <= 4096 }) integrated else environment
}
