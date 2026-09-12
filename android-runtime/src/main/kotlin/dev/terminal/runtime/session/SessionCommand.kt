package dev.terminal.runtime.session

/** Length-prefixed UTF-8 argv envelope, removed before constructing the child environment. */
internal object SessionCommand {
    const val KEY = "DROID_SESSION_ARGV"
    fun encode(args: List<String>): String {
        require(args.size in 1..64 && args.first().isNotEmpty() && !args.first().startsWith('-') && '=' !in args.first()) { "Invalid command" }
        require(args.none { '\u0000' in it }) { "Invalid command argument" }
        return buildString {
            append(args.size).append(':')
            args.forEach { append(it.toByteArray().size).append(':').append(it) }
        }.also { require(it.toByteArray().size <= 4096) { "Command arguments exceed 4096 bytes" } }
    }
    fun decode(env: Map<String, String>): List<String>? = env[KEY]?.let { encoded ->
        val bytes = encoded.toByteArray()
        require(bytes.size <= 4096) { "Command too large" }
        var offset = 0
        fun number(): Int {
            val start = offset
            while (offset < bytes.size && bytes[offset] != 58.toByte()) {
                require(bytes[offset] in 48..57 && offset - start < 5) { "Invalid command encoding" }
                offset++
            }
            require(offset > start && offset < bytes.size) { "Truncated command" }
            return String(bytes, start, offset++ - start, Charsets.US_ASCII).toInt()
        }
        val count = number()
        require(count in 1..64) { "Invalid command arguments" }
        val args = List(count) {
            val size = number()
            require(size <= bytes.size - offset) { "Truncated argument" }
            bytes.decodeToString(offset, offset + size, throwOnInvalidSequence = true).also { offset += size }
        }
        require(offset == bytes.size) { "Trailing command bytes" }
        encode(args)
        args
    }
}
