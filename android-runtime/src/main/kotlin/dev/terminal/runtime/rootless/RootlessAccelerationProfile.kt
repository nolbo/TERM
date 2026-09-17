package dev.terminal.runtime.rootless

import java.io.File

/** APK-selected profile validated with fresh Debian installation and file workloads. */
internal class RootlessAccelerationProfile(library: File) {
    private val guestLibrary = "/usr/local/lib/terminal-faststat.so"
    val binding = "${library.absolutePath}:$guestLibrary"
    val hostEnvironment = arrayOf(
        "PROOT_STAT_ENTRY=1", "PROOT_STAT_KERNEL=1", "PROOT_PATH_KERNEL=1",
        "PROOT_MUTATION_ENTRY=1", "PROOT_FSTAT_ENTRY=1", "PROOT_FASTSTAT=2",
        "PROOT_FASTFD=1", "PROOT_FASTPATH=1", "PROOT_TCGETS_DIRECT=1", "PROOT_ID_ENTRY=1",
    )

    fun guestEnvironment(preload: String?) = linkedMapOf(
        "LD_PRELOAD" to listOfNotNull(guestLibrary, preload?.takeIf { it.isNotBlank() }).joinToString(":"),
        "PROOT_FASTFD" to "1", "PROOT_FASTOPEN" to "1", "PROOT_FASTCREATE" to "1",
        "PROOT_FASTUNLINK" to "1", "PROOT_FASTSTAT_PATH" to "0", "PROOT_FASTSTAT_SHARED" to "1",
    )
}
