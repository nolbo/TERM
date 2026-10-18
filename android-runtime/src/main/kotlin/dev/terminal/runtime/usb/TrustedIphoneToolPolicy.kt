package dev.terminal.runtime.usb

internal object TrustedIphoneToolPolicy {
    const val SERVICE_REVISION = 4
    enum class Phase { IDLE, WAITING_FOR_USB_PERMISSION, CONNECTING, RUNNING, COMPLETED, CANCELED, FAILED }
    enum class Error {
        USB_HOST_UNAVAILABLE, USB_PERMISSION_DENIED, UNSUPPORTED_USB_DEVICE, USB_CONFIGURATION_REQUIRED,
        CANCELED, USB_DETACHED, DEVICE_BUSY, WORKER_AUTHENTICATION, WORKER_PROTOCOL,
    }
    fun validateRequest(action: String, keys: Set<String>) {
        val extra = when (action) {
            "list" -> emptySet()
            "check" -> setOf("device")
            else -> error("UNSUPPORTED_TOOL_OPERATION")
        }
        val expected = setOf("version", "token", "op", "action") + extra
        require(keys == expected) { "INVALID_TOOL_REQUEST" }
    }
}

internal class TrustedIphoneToolStateMachine {
    var phase = TrustedIphoneToolPolicy.Phase.IDLE
        private set
    fun transition(next: TrustedIphoneToolPolicy.Phase) {
        val allowed = when (phase) {
            TrustedIphoneToolPolicy.Phase.IDLE -> TrustedIphoneToolPolicy.Phase.WAITING_FOR_USB_PERMISSION
            TrustedIphoneToolPolicy.Phase.WAITING_FOR_USB_PERMISSION -> TrustedIphoneToolPolicy.Phase.CONNECTING
            TrustedIphoneToolPolicy.Phase.CONNECTING -> TrustedIphoneToolPolicy.Phase.RUNNING
            TrustedIphoneToolPolicy.Phase.RUNNING -> TrustedIphoneToolPolicy.Phase.COMPLETED
            else -> null
        }
        check(next == allowed)
        phase = next
    }
    fun stop(error: TrustedIphoneToolPolicy.Error) {
        if (phase !in setOf(TrustedIphoneToolPolicy.Phase.COMPLETED, TrustedIphoneToolPolicy.Phase.CANCELED, TrustedIphoneToolPolicy.Phase.FAILED))
            phase = if (error == TrustedIphoneToolPolicy.Error.CANCELED) TrustedIphoneToolPolicy.Phase.CANCELED else TrustedIphoneToolPolicy.Phase.FAILED
    }
}
