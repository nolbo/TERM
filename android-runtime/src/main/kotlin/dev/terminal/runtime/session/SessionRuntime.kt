package dev.terminal.runtime.session

/** User-visible runtime choice. Commands never migrate between these values implicitly. */
enum class SessionRuntime(val wireName: String, val idPrefix: String) {
    NATIVE("NATIVE", "native-"),
    ROOTLESS("ROOTLESS", "rootless-"),
    AVF("AVF", "avf-"),
    ;

    companion object {
        fun fromWireName(value: String): SessionRuntime? = entries.firstOrNull {
            it.wireName.equals(value, ignoreCase = true)
        }

        fun fromSessionId(id: String): SessionRuntime? = entries.firstOrNull { id.startsWith(it.idPrefix) }
    }
}
