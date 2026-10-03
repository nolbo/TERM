package dev.terminal.runtime.avf.system

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.system.virtualmachine.VirtualMachineCustomImageConfig
import dev.terminal.runtime.api.RuntimeBackend
import dev.terminal.runtime.avf.AvfBackend

/** Loads the optional device API only after the Android capability boundary permits AVF. */
object AospAvfRuntime {
    fun createBackend(context: Context): RuntimeBackend {
        check(Build.VERSION.SDK_INT >= 35 &&
            context.packageManager.hasSystemFeature("android.software.virtualization_framework")) {
            "This device does not support the AVF custom-image runtime"
        }
        check(context.checkSelfPermission("android.permission.MANAGE_VIRTUAL_MACHINE") == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission("android.permission.USE_CUSTOM_VIRTUAL_MACHINE") == PackageManager.PERMISSION_GRANTED) {
            "AVF requires MANAGE_VIRTUAL_MACHINE and USE_CUSTOM_VIRTUAL_MACHINE system permissions"
        }
        return requireAvfPlatformApi {
            // Configuration allocation is side-effect free: check access before the
            // runtime owner enqueues a multi-gigabyte image download or creates a VM.
            VirtualMachineCustomImageConfig.Builder()
            AvfBackend(AospAvfPlatformDriver(context))
        }
    }
}
