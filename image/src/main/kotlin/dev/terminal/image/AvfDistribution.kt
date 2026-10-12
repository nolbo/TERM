package dev.terminal.image

/** Distribution identity is independent of AVF VM/session ownership and disk layout. */
enum class AvfDistribution(val id: String, val displayName: String, val packageManager: String) {
    DEBIAN("debian", "Debian", "apt"),
    UBUNTU("ubuntu", "Ubuntu 24.04 LTS", "apt"),
    ARCH("archlinux", "Arch Linux ARM", "pacman"),
    ;

    val releaseId: String get() = "$id-arm64-current"

    companion object {
        fun fromId(id: String): AvfDistribution = entries.firstOrNull { it.id == id }
            ?: error("Unsupported AVF distribution: $id")
    }
}
