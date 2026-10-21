package dev.terminal.app

import android.content.Context
import dev.terminal.bridge.AndroidCapabilityBroker
import dev.terminal.bridge.BridgeResult
import dev.terminal.bridge.Capability
import dev.terminal.bridge.CapabilityGrant
import dev.terminal.bridge.SharedPreferencesCapabilityGrantStore
import dev.terminal.runtime.session.SessionRuntime

/** Host-selected scope, separate from all AVF instance grants. No clipboard read. */
internal const val NATIVE_CLIPBOARD_SCOPE = "host-native-clipboard"

internal fun nativeClipboardEnabled(context: Context): Boolean =
    SharedPreferencesCapabilityGrantStore(context).get(NATIVE_CLIPBOARD_SCOPE, Capability.CLIPBOARD_WRITE) == CapabilityGrant.ALLOW_ALWAYS

internal fun setNativeClipboardEnabled(context: Context, enabled: Boolean) {
    SharedPreferencesCapabilityGrantStore(context).set(NATIVE_CLIPBOARD_SCOPE, Capability.CLIPBOARD_WRITE,
        if (enabled) CapabilityGrant.ALLOW_ALWAYS else CapabilityGrant.DENY)
}

internal fun copyNativeClipboard(context: Context, sessionId: String, text: String, focusedForeground: Boolean): Boolean {
    if (!focusedForeground || SessionRuntime.fromSessionId(sessionId) != SessionRuntime.NATIVE || text.toByteArray().size > 32768) return false
    if (!nativeClipboardEnabled(context)) return false
    return AndroidCapabilityBroker(context, NATIVE_CLIPBOARD_SCOPE).clipboardSet(text) is BridgeResult.Success
}
