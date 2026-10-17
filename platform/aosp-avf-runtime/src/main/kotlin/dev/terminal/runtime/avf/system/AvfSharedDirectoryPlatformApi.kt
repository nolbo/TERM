package dev.terminal.runtime.avf.system

import android.content.Context
import android.os.Build
import android.system.virtualmachine.VirtualMachineCustomImageConfig
import dev.terminal.avf.compat.SharedPathCompat
import java.io.File

/** Startup and diagnostics use the same versioned direct calls, without an API-policy bypass. */
internal fun avfSharedPath(export: AvfSharedDirectories.Export): VirtualMachineCustomImageConfig.SharedPath = SharedPathCompat.create(
    Build.VERSION.SDK_INT >= 37, export.directory.path, android.os.Process.myUid(), android.system.Os.getgid(),
    AvfSharedDirectories.GUEST_UID, AvfSharedDirectories.GUEST_GID, 7,
    export.tag, export.socket.name, export.socket.path,
)

internal fun checkAvfSharedDirectoryApi(context: Context) {
    // Allocate inert configuration objects only. Do not build a VM config, create files,
    // export a directory, start a server, or contact VirtualMachineManager.
    val directory = File(context.filesDir, ".avf-sharing-api-probe")
    val export = AvfSharedDirectories.Export(directory, "term-api-probe", File(directory, "socket"))
    avfCustomImageBuilder().addSharedPath(avfSharedPath(export))
}
