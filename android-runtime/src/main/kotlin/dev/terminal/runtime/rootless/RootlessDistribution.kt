package dev.terminal.runtime.rootless

import dev.terminal.runtime.session.SessionRuntime

/** Approved ARM64 rootfs catalog; host executables always remain APK payloads.
 * Source: termux/proot-distro v4.29.0 distro-plugins/{debian,ubuntu,archlinux}.sh.
 */
enum class RootlessDistribution(
    val directory: String,
    val displayName: String,
    val runtime: SessionRuntime,
    val archiveRoot: String,
    val release: String,
    val sha256: String,
    val packageExecutables: List<String>,
    val minimumFreeMiB: Long,
    val maximumArchiveMiB: Long,
) {
    DEBIAN("debian", "Debian", SessionRuntime.ROOTLESS,
        "debian-trixie-aarch64", "v4.29.0",
        "3834a11cbc6496935760bdc20cca7e2c25724d0cd8f5e4926da8fd5ca1857918",
        listOf("usr/bin/apt", "usr/bin/apt-get"), 2048, 128),
    UBUNTU("ubuntu", "Ubuntu 24.04 LTS", SessionRuntime.ROOTLESS_UBUNTU,
        "ubuntu-noble-aarch64", "v4.18.0",
        "91acaa786b8e2fbba56a9fd0f8a1188cee482b5c7baeed707b29ddaa9a294daa",
        listOf("usr/bin/apt", "usr/bin/apt-get"), 4096, 256),
    ARCH("archlinux", "Arch Linux ARM", SessionRuntime.ROOTLESS_ARCH,
        "archlinux-aarch64", "v4.22.1",
        "b7e4cfb1414a281f90bfd39a503f72f38e03c31b356927972f797988fb48b5b1",
        listOf("usr/bin/pacman"), 2048, 256),
    ;

    val archiveName: String get() = "$archiveRoot-pd-$release.tar.xz"
    val archiveUrl: java.net.URI get() = java.net.URI(
        "https://github.com/termux/proot-distro/releases/download/$release/$archiveName",
    )
}
