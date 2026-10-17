package dev.terminal.runtime.avf.system

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Checks configuration allocation and ABI only; never constructs a driver or a VM. */
fun inspectAvfDiagnostics(context: Context): AvfDiagnostics = collectAvfDiagnostics(object : AvfDiagnosticProbe {
    override fun sdk() = Build.VERSION.SDK_INT
    override fun feature() = context.packageManager.hasSystemFeature(AVF_FEATURE)
    override fun permission(name: String) = context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
    override fun customImageApi() = checkAvfCustomImageApi()
    override fun manager() = avfVirtualMachineManager(context) != null
    override fun driverPackaged(): Boolean = try {
        Class.forName("dev.terminal.runtime.avf.system.AospAvfPlatformDriver", false, context.classLoader)
        true
    } catch (_: ClassNotFoundException) { false }
    override fun driverCompatible() = checkAvfDriverAbi()
    override fun sharedDirectoryApi() = checkAvfSharedDirectoryApi(context)
})
