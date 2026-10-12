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
 * Implement this on the application's [android.app.Application] class in an
 * OEM/system image build. The same Application instance is available in the
 * `:vmhost` process, so selection still occurs where the VM is owned.
 */
interface RuntimeBackendProvider {
    fun createRuntimeBackend(context: Context): RuntimeBackend
}

internal fun resolveRuntimeBackend(context: Context): RuntimeBackend =
    (context.applicationContext as? RuntimeBackendProvider)
        ?.createRuntimeBackend(context.applicationContext)
        ?: NativeBackend(context.applicationContext)
