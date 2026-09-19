package dev.terminal.runtime.rootless

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootlessLaunchSpecTest {
    @Test fun stdioBufferingIsExplicitAndGuestOnly() {
        val library = temporary.newFile("stdio-profile.so")
        val ordinary = RootlessAccelerationProfile(library, stdioBuffering = false)
        val buffered = RootlessAccelerationProfile(library, stdioBuffering = true)
        assertFalse(ordinary.guestEnvironment(null).containsKey("PROOT_STDIO_BUFFER"))
        assertEquals("1", buffered.guestEnvironment(null)["PROOT_STDIO_BUFFER"])
        assertFalse(buffered.hostEnvironment.any { it.startsWith("PROOT_STDIO_BUFFER=") })
    }

    @Test fun nssBindingsAreSelectedByRuntimeAndDoNotReplaceAccountFiles() {
        val layout = RootlessDebianLayout(File(temporary.root, "nss/debian"))
        val bindings = listOf("/private/run:/run/nscd", "/private/cache:/var/cache/nscd")
        val spec = buildRootlessLaunchSpec(layout, File("/proot"), File("/loader"),
            "/bin/sh", "/root", emptyMap(), nssBindings = bindings)
        val actual = spec.arguments.toList().windowed(2).filter { it[0] == "-b" }.map { it[1] }
        assertTrue(actual.containsAll(bindings))
        assertFalse(actual.any { it.endsWith(":/etc/passwd") || it.endsWith(":/etc/group") })
        assertFalse(spec.hostEnvironment.contains("PROOT_OWNER_PIPE=1"))
    }

    @Test fun accelerationIsHostSelectedAndPreloadStaysInGuest() {
        val layout = RootlessDebianLayout(File(temporary.root, "accelerated/debian"))
        layout.rootfs.mkdirs()
        RootlessStorageFormat.prepareNewInstallation(layout.rootfs.toPath())
        val library = temporary.newFile("libproot-faststat.so")
        val spec = buildRootlessLaunchSpec(layout, File("/proot"), File("/loader"), "/bin/sh", "/root",
            mapOf("LD_PRELOAD" to "/guest/custom.so", "PROOT_FASTSTAT_SHARED" to "0",
                "PROOT_OPEN_NOTIFY" to "1", "PROOT_STDIO_BUFFER" to "forged"), accelerationLibrary = library)
        assertTrue(spec.arguments.contains("${library.absolutePath}:/usr/local/lib/terminal-faststat.so"))
        assertTrue(spec.arguments.contains("LD_PRELOAD=/usr/local/lib/terminal-faststat.so:/guest/custom.so"))
        assertTrue(spec.arguments.contains("PROOT_FASTSTAT_SHARED=1"))
        assertFalse(spec.arguments.contains("PROOT_FASTSTAT_SHARED=0"))
        assertFalse(spec.arguments.contains("PROOT_OPEN_NOTIFY=1"))
        assertFalse(spec.arguments.contains("PROOT_STDIO_BUFFER=forged"))
        assertEquals(dev.terminal.runtime.BuildConfig.ROOTLESS_STDIO_BUFFER,
            spec.arguments.contains("PROOT_STDIO_BUFFER=1"))
        assertFalse(spec.arguments.contains("--link2symlink"))
        assertTrue(spec.hostEnvironment.contains("PROOT_FASTPATH=1"))
        assertTrue(spec.hostEnvironment.contains("PROOT_FASTCLOSE=1"))
        assertTrue(spec.arguments.contains("PROOT_FASTCLOSE=1"))
        assertFalse(spec.hostEnvironment.any { it.startsWith("LD_PRELOAD=") })
    }

    @Test fun verifiedEmulatedStorageUsesProotWithoutNativeAcceleration() {
        val layout = RootlessDebianLayout(File(temporary.root, "emulated/ubuntu"), RootlessDistribution.UBUNTU)
        layout.rootfs.mkdirs()
        RootlessStorageFormat.prepareNewInstallation(layout.rootfs.toPath()) { _, _ ->
            throw java.nio.file.AccessDeniedException("hardlink")
        }
        val library = temporary.newFile("fallback-stat.so")
        val spec = buildRootlessLaunchSpec(layout, File("/proot"), File("/loader"),
            "/bin/sh", "/root", emptyMap(), accelerationLibrary = library)
        assertTrue(spec.arguments.contains("--link2symlink"))
        assertFalse(spec.arguments.any { it.contains("terminal-faststat.so") || it.startsWith("PROOT_FAST") })
        assertFalse(spec.hostEnvironment.any { it.startsWith("PROOT_FAST") || it.startsWith("PROOT_STAT") })
    }

    @Test fun accelerationRejectsMissingLibraryButMigratesUnmarkedRootfs() {
        val layout = RootlessDebianLayout(File(temporary.root, "unmarked/debian"))
        fun launch(library: File) = buildRootlessLaunchSpec(layout, File("/proot"), File("/loader"),
            "/bin/sh", "/root", emptyMap(), accelerationLibrary = library)
        assertTrue(runCatching { launch(File(temporary.root, "missing.so")) }.exceptionOrNull() is IllegalArgumentException)
        val spec = launch(temporary.newFile("present.so"))
        assertTrue(spec.arguments.contains("--link2symlink"))
        assertFalse(spec.arguments.any { it.contains("terminal-faststat.so") })
        assertFalse(spec.hostEnvironment.any { it.startsWith("PROOT_FAST") })
    }

    @Test fun usbCredentialsAndClientBindAreHostSelected() {
        val layout = RootlessDebianLayout(File(temporary.root, "rootless/debian"))
        val client = File(temporary.root, "usb-client.py")
        fun spec(hostEnv: Map<String, String> = emptyMap(), script: File? = null) = buildRootlessLaunchSpec(
            layout, File("/proot"), File("/loader"), "/bin/sh", "/root",
            mapOf("TERMINAL_USB_TOKEN" to "guest-forged", "TERMINAL_USB_SOCKET" to "guest-socket"),
            usbClientFile = script, usbEnvironment = hostEnv,
        ).arguments.toList()
        assertFalse(spec().any { it.startsWith("TERMINAL_USB_") })
        val enabled = spec(mapOf("TERMINAL_USB_TOKEN" to "host-token", "TERMINAL_USB_SOCKET" to "host-socket"), client)
        assertTrue(enabled.contains("TERMINAL_USB_TOKEN=host-token"))
        assertTrue(enabled.contains("${client.absolutePath}:/usr/local/bin/terminal-usb"))
        assertFalse(enabled.any { it.contains("guest-forged") || it.contains("guest-socket") })
    }
    @get:Rule val temporary = TemporaryFolder()

    @Test fun sharedStorageIsOptInAndCannotBeEnabledByGuestEnvironment() {
        val layout = RootlessDebianLayout(File(temporary.root, "rootless/debian"))
        val proot = temporary.newFile("proot")
        val loader = temporary.newFile("loader")
        val storage = File("/storage/emulated/0")
        val env = mapOf("EXTERNAL_STORAGE" to storage.path, "SHARED_STORAGE" to storage.path)
        fun launch(grantedPath: File? = null) = buildRootlessLaunchSpec(
            layout, proot, loader, "/bin/sh", "/root", env, grantedPath,
        ).arguments.toList().windowed(2).filter { it.first() == "-b" }.map { it.last() }

        val denied = launch()
        assertFalse(denied.any { it.contains("/sdcard") || it.contains(storage.path) })
        assertEquals(denied + "${storage.path}:/sdcard", launch(storage))
        assertEquals(denied, launch(null)) // A later session after revocation has no bind.
        assertTrue(denied.contains("${layout.workspace.absolutePath}:/workspace"))
        assertFalse(launch(storage).contains(layout.root.parentFile.parentFile.absolutePath))
    }

    @Test fun remoteArgvPreservesLiteralArgumentsAndRemovesInternalEnvelope() {
        val argv = listOf("/bin/printf", "%s", "a b", "\$(touch nope)", "한글", "")
        val launch = buildRootlessLaunchSpec(
            RootlessDebianLayout(File(temporary.root, "rootless/debian")),
            temporary.newFile("proot"), temporary.newFile("loader"), "/bin/bash", "/workspace",
            mapOf(dev.terminal.runtime.session.SessionCommand.KEY to dev.terminal.runtime.session.SessionCommand.encode(argv)),
        )
        assertEquals(argv, launch.arguments.takeLast(argv.size))
        assertFalse(launch.arguments.any { it.startsWith("DROID_SESSION_ARGV=") })
        assertFalse(launch.arguments.contains("-l"))
    }

    @Test fun suppliesExecArgvLoaderBindingsAndSanitizedGuestEnvironment() {
        val layout = RootlessDebianLayout(File(temporary.root, "rootless/debian"))
        layout.createHostDirectories()
        val proot = temporary.newFile("proot")
        val loader = temporary.newFile("loader")

        val launch = buildRootlessLaunchSpec(
            layout,
            proot,
            loader,
            shell = "/bin/bash",
            cwd = "/workspace",
            env = mapOf(
                "TERM" to "xterm-256color",
                "DROID_LOGIN_SHELL" to "true",
                "SAFE_NAME" to "value",
                "PATH" to "/host/path",
                "INVALID-NAME" to "secret",
            ),
        )

        assertEquals(proot.absolutePath, launch.command)
        assertEquals(proot.absolutePath, launch.arguments.first())
        assertTrue(launch.arguments.toList().windowed(2).contains(listOf("-r", layout.rootfs.absolutePath)))
        assertTrue(launch.arguments.toList().windowed(2).contains(listOf("-w", "/workspace")))
        assertTrue(launch.arguments.contains("${layout.home.absolutePath}:/root"))
        assertTrue(launch.arguments.contains("${layout.workspace.absolutePath}:/workspace"))
        assertTrue(launch.arguments.contains("SAFE_NAME=value"))
        assertFalse(launch.arguments.contains("PATH=/host/path"))
        assertFalse(launch.arguments.any { it.startsWith("INVALID-NAME=") })
        assertEquals(listOf("/bin/bash", "-l"), launch.arguments.takeLast(2))
        assertTrue(launch.hostEnvironment.contains("PROOT_LOADER=${loader.absolutePath}"))
        assertTrue(launch.hostEnvironment.contains("PROOT_TMP_DIR=${layout.tmp.absolutePath}"))
    }
}
