package dev.terminal.runtime.avf

import android.content.Context
import android.content.pm.PackageManager
import dev.terminal.image.DebianImageVerifier
import dev.terminal.image.ImageVerification
import dev.terminal.runtime.api.GuestConnection
import dev.terminal.runtime.api.RuntimeConfig
import dev.terminal.runtime.api.StopMode
import java.nio.file.Paths

/**
 * Public-SDK-safe AVF deployment probe. It never uses hidden APIs or assumes
 * that declaring a privileged permission grants it.
 */
class PlatformProbeAvfDriver(private val context: Context) : AvfPlatformDriver {
    override suspend fun prepare(config: RuntimeConfig): Result<Unit> = runCatching {
        require(config.imageDirectory.isNotBlank()) { "A Debian image directory is required" }
        when (val verification = DebianImageVerifier.verify(Paths.get(config.imageDirectory))) {
            is ImageVerification.Invalid -> error(verification.reason)
            is ImageVerification.Ready -> Unit
        }
        require(context.packageManager.hasSystemFeature(AVF_FEATURE)) {
            "This device does not advertise Android Virtualization Framework support"
        }
        require(hasPrivilegedAvfAccess()) {
            "AVF custom VM access requires a platform-signed or OEM deployment"
        }
        error("AVF system-SDK driver has not been linked into this build")
    }

    override suspend fun start(config: RuntimeConfig): Result<Unit> = Result.failure(
        UnsupportedOperationException("Prepare must succeed before AVF can start"),
    )

    override suspend fun stop(mode: StopMode): Result<Unit> = Result.success(Unit)

    override suspend fun connectGuestAgent(): Result<GuestConnection> = Result.failure(
        IllegalStateException("No guest agent is connected"),
    )

    private fun hasPrivilegedAvfAccess(): Boolean =
        context.checkSelfPermission(MANAGE_VIRTUAL_MACHINE) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(USE_CUSTOM_VIRTUAL_MACHINE) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val AVF_FEATURE = "android.software.virtualization_framework"
        const val MANAGE_VIRTUAL_MACHINE = "android.permission.MANAGE_VIRTUAL_MACHINE"
        const val USE_CUSTOM_VIRTUAL_MACHINE = "android.permission.USE_CUSTOM_VIRTUAL_MACHINE"
    }
}
