package dev.terminal.runtime.lifecycle

import android.content.Context
import dev.terminal.runtime.api.RuntimeBackend

/**
 * Factory for an Environment's AVF owner without leaking AOSP
 * System APIs into the public-SDK runtime module.
 *
 * Implement this on the application's [android.app.Application] class.
 * Packaging the driver does not grant access to the device's platform API.
 * The same Application instance is available in the `:vmhost` process, so selection
 * still occurs where the VM is owned.
 */
interface RuntimeBackendProvider {
    /** Fresh AVF owner for an explicitly selected storage. */
    fun createAvfBackend(context: Context): RuntimeBackend
}
