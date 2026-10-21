package dev.terminal.bridge

/** Capabilities are guest grants, deliberately distinct from Android OS permissions. */
enum class Capability(val wireName: String) {
    CLIPBOARD_READ("clipboard.read"),
    CLIPBOARD_WRITE("clipboard.write"),
    INTENT_OPEN_URL("intent.openUrl"),
    NOTIFICATION_POST("notification.post"),
    DEVICE_BATTERY("device.battery"),
    DEVICE_NETWORK("device.network"),
}

enum class CapabilityGrant {
    DENY,
    ASK,
    ALLOW_ONCE,
    ALLOW_WHILE_VM_RUNNING,
    ALLOW_ALWAYS,
}

sealed interface BridgeResult<out T> {
    data class Success<T>(val value: T) : BridgeResult<T>
    data class Denied(val capability: Capability, val reason: String) : BridgeResult<Nothing>
    data class Invalid(val reason: String) : BridgeResult<Nothing>
    data class Unavailable(val reason: String) : BridgeResult<Nothing>
}

/** Host-selected execution scopes: VM identity or a fixed host Native scope, never a guest-provided label. */
interface CapabilityGrantStore {
    fun get(vmInstanceId: String, capability: Capability): CapabilityGrant
    fun set(vmInstanceId: String, capability: Capability, grant: CapabilityGrant)
    fun consumeOnce(vmInstanceId: String, capability: Capability): Boolean
}
