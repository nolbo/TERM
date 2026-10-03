package dev.terminal.runtime.lifecycle

import android.content.Context
import dev.terminal.runtime.api.RuntimeBackend
import dev.terminal.runtime.avf.AvfBackend
import dev.terminal.runtime.avf.PlatformProbeAvfDriver
import dev.terminal.runtime.native.NativeBackend

/**
 * Product-variant seam for selecting a runtime owner without leaking AOSP
 * System APIs into the public-SDK runtime module.
 *
 * Implement this on the application's [android.app.Application] class.
 * Packaging the driver does not grant access to the device's platform API.
 * The same Application instance is available in the `:vmhost` process, so selection
 * still occurs where the VM is owned.
 */
interface RuntimeBackendProvider {
    /** Fresh AVF owner for an explicitly selected storage. */
    fun createRuntimeBackend(context: Context): RuntimeBackend

    /** Legacy/default owner; ordinary application startup must not require AVF. */
    fun createDefaultRuntimeBackend(context: Context): RuntimeBackend = createRuntimeBackend(context)
}

internal fun resolveRuntimeBackend(context: Context): RuntimeBackend =
    (context.applicationContext as? RuntimeBackendProvider)
        ?.createDefaultRuntimeBackend(context.applicationContext)
        // The service's readiness controller owns no profile environment. Migrated Native
        // storage remains at files/native and receives a separate UUID-scoped controller.
        ?: NativeBackend(context.applicationContext, java.io.File(context.filesDir, "native-runtime"))
