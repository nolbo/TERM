package dev.terminal.runtime.usb

/** Whole-device ownership is explicit; a negative interface never means 'no resources'. */
internal data class UsbAccessScope(val device: String, val interfaceId: Int, val exclusive: Boolean) {
    fun conflicts(other: UsbAccessScope): Boolean = device == other.device &&
        (exclusive || other.exclusive || interfaceId == other.interfaceId)

    companion object {
        /** These upstream drivers address vendor control by port index, not arbitrary interface ID. */
        fun isolatedPort(driver: String, configurations: Int, ids: List<Int>, alternates: List<Int>, ports: Int, port: Int): Int =
            if (driver in setOf("ftdi", "cp21xx") && configurations == 1 && port in 0 until ports &&
                ids.size == ports && alternates.size == ports && alternates.all { it == 0 } &&
                ids.withIndex().all { it.value == it.index }) ids[port] else -1
    }
}
