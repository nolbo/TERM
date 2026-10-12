package dev.terminal.runtime.rootless

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE

/** Seed only shell defaults. Never copy image credentials or overwrite existing user files/links. */
internal fun configureRootlessLoginHome(layout: RootlessDebianLayout) {
    val home = layout.home.toPath()
    require(Files.isDirectory(home, NOFOLLOW_LINKS)) { "배포판 홈은 실제 디렉터리여야 합니다" }
    fun seed(name: String, required: Boolean = true) {
        if (Files.exists(home.resolve(name), NOFOLLOW_LINKS)) return
        val content = readRootlessFile(layout.rootfs, "root/$name")
            ?: readRootlessFile(layout.rootfs, "etc/skel/$name")
        if (content == null) {
            check(!required) { "배포판 이미지에 기본 $name 파일이 없습니다" }
            return
        }
        try {
            Files.write(home.resolve(name), content.toByteArray(Charsets.UTF_8), CREATE_NEW, WRITE)
        } catch (_: FileAlreadyExistsException) { /* Preserve concurrent user creation. */ }
    }
    seed(".bashrc")
    // A new .bash_profile must not shadow an existing user .profile or .bash_login.
    if (listOf(".bash_profile", ".bash_login", ".profile").none { Files.exists(home.resolve(it), NOFOLLOW_LINKS) }) {
        seed(if (layout.distribution == RootlessDistribution.ARCH) ".bash_profile" else ".profile")
    }
    seed(".bash_logout", required = false)
    if (layout.distribution == RootlessDistribution.UBUNTU) {
        // Suppress Ubuntu's sudo hint, whose `groups` lookup sees Android supplementary GIDs.
        try { Files.createFile(home.resolve(".hushlogin")) } catch (_: FileAlreadyExistsException) { }
    }
}
