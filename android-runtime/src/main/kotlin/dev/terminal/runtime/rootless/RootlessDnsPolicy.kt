package dev.terminal.runtime.rootless

import java.net.InetAddress

/** Engine-wide policy; no Environment or Profile can override it. */
@ConsistentCopyVisibility
data class RootlessDnsPolicy private constructor(val mode: String, val servers: List<String>) {
    fun resolverConfig(androidServers: List<String>): String {
        val selected = if (mode == CUSTOM) servers else androidServers.distinct().take(MAX_SERVERS)
        return "# Managed by TERM Userspace Linux\n" + selected.joinToString("") { "nameserver $it\n" }
    }

    companion object {
        const val ANDROID = "android"
        const val CUSTOM = "custom"
        const val MAX_SERVERS = 3
        val DEFAULT = RootlessDnsPolicy(ANDROID, emptyList())

        fun parse(mode: String, input: String): RootlessDnsPolicy {
            require(mode == ANDROID || mode == CUSTOM) { "Unknown DNS policy" }
            if (mode == ANDROID) return DEFAULT
            require(input.length <= 512) { "DNS input is too long" }
            val servers = input.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.distinct()
            require(servers.size in 1..MAX_SERVERS && servers.all(::isNumericAddress)) { "Enter one to three IPv4 or IPv6 addresses" }
            return RootlessDnsPolicy(CUSTOM, servers.toList())
        }

        private fun isNumericAddress(value: String): Boolean {
            if (':' in value) {
                // Guard before getByName: these characters can only form a literal,
                // so validation never performs a hostname lookup.
                return value.matches(Regex("[0-9a-fA-F:.]+")) && runCatching { InetAddress.getByName(value) }.isSuccess
            }
            val parts = value.split('.')
            return parts.size == 4 && parts.all {
                it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() in 0..255
            }
        }
    }
}
