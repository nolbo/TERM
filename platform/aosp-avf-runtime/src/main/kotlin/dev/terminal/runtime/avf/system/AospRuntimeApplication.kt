package dev.terminal.runtime.avf.system

import android.app.Application
import android.content.Context
import dev.terminal.runtime.api.RuntimeBackend
import dev.terminal.runtime.avf.AvfBackend
import dev.terminal.runtime.lifecycle.RuntimeBackendProvider

/** Product-only Application selected by the privileged system-image manifest. */
class AospRuntimeApplication : Application(), RuntimeBackendProvider, androidx.work.Configuration.Provider {
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().setDefaultProcessName(packageName).build()
    override fun createRuntimeBackend(context: Context): RuntimeBackend =
        AvfBackend(AospAvfPlatformDriver(context))
}
