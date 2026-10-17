package dev.terminal.runtime.avf.system

import android.content.Context
import android.system.virtualmachine.VirtualMachineCustomImageConfig
import java.io.File

/** Both startup and diagnostics exercise the same direct constructor, without an API-policy bypass. */
internal fun avfSharedPath(export: AvfSharedDirectories.Export) = VirtualMachineCustomImageConfig.SharedPath(
    export.directory.path, android.os.Process.myUid(), android.system.Os.getgid(),
    AvfSharedDirectories.GUEST_UID, AvfSharedDirectories.GUEST_GID, 7,
    export.tag, export.socket.name, true, export.socket.path,
)

internal fun checkAvfSharedDirectoryApi(context: Context) {
    // Allocate inert configuration objects only. Do not build a VM config, create files,
    // export a directory, start a server, or contact VirtualMachineManager.
    val directory = File(context.filesDir, ".avf-sharing-api-probe")
    val export = AvfSharedDirectories.Export(directory, "term-api-probe", File(directory, "socket"))
    avfCustomImageBuilder().addSharedPath(avfSharedPath(export))
}
