package dev.terminal.runtime.usb

/** A typed operation, never an arbitrary libimobiledevice command or service port. */
internal object IphonePolicy {
    val operations = setOf("pair", "info", "unpair", "afc-list", "afc-get", "afc-put")
    val infoKeys = setOf("DeviceName", "ProductType", "ProductVersion", "BuildVersion", "DeviceClass")
    const val MAX_REPLY = 8192
    const val DEADLINE_MS = 27_000L
    const val FILE_LIMIT = 64 * 1024 * 1024
    const val CHUNK = 16 * 1024
    const val AFC_DEADLINE_MS = 125_000L

    fun path(value: String): String = value.also {
        require(it.startsWith('/') && it.toByteArray(Charsets.UTF_8).size <= 1024 &&
            it.none { c -> c.code < 32 || c.code == 127 || c == '\\' } &&
            (it == "/" || it.substring(1).split('/').all { p -> p.isNotEmpty() && p != "." && p != ".." })) {
            "INVALID_AFC_PATH"
        }
    }

    fun uploadName(value: String): String = value.also {
        require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}"))) { "INVALID_UPLOAD_NAME" }
    }

    fun size(value: Any): Int {
        require(value is Int && value in 0..FILE_LIMIT) { "INVALID_FILE_SIZE" }
        return value
    }

    fun hex(value: String): ByteArray {
        require(value.length in 2..CHUNK * 2 && value.length % 2 == 0 && value.all { it in "0123456789abcdef" }) {
            "INVALID_FILE_CHUNK"
        }
        return ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun operation(value: String): String = value.also {
        require(it in operations) { "UNSUPPORTED_IPHONE_OPERATION" }
    }

    fun candidate(vendor: Int, product: Int): Boolean = vendor == 0x05ac && product in 0x1290..0x12af
}
