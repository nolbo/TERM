package dev.terminal.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.runtime.rootless.RootlessDebianBackend
import dev.terminal.runtime.rootless.RootlessDistribution
import dev.terminal.runtime.rootless.RootlessPhase
import dev.terminal.runtime.rootless.RootlessStatus
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real package operations in separate test storage. Never touches the user's distribution trees. */
@RunWith(AndroidJUnit4::class)
class RootlessDefaultEnvironmentTest {
    @Test fun distributionDefaultsAndExistingHomeSurvivePreparationAndRetry() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val selected = InstrumentationRegistry.getArguments().getString("distribution")
        for (distribution in RootlessDistribution.entries.filter { selected == null || it.directory == selected }) {
            // Kept on failure for diagnosis/resume; package manager locks are never force-deleted.
            val testRoot = File(target.cacheDir, "default-environment-test/${distribution.directory}").apply { mkdirs() }
            val isolated = object : ContextWrapper(target) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = File(testRoot, "files").apply { mkdirs() }
                override fun getCacheDir(): File = File(testRoot, "cache").apply { mkdirs() }
            }
            val root = File(isolated.filesDir, "rootless/${distribution.directory}")
            val home = File(root, "home").apply { mkdirs() }
            val customLogin = File(home, ".profile").apply { writeText("# retain custom login\n") }
            var injectFailure = !File(root, "rootfs").exists()
            var packages = 0
            val phases = mutableListOf<RootlessPhase>()
            val backend = RootlessDebianBackend(isolated, distribution = distribution) { phase, _ ->
                synchronized(phases) { phases += phase }
                android.util.Log.i("RootlessDefaultsTest", "${distribution.directory} $phase")
                if (phase == RootlessPhase.PACKAGES) {
                    packages++
                    if (injectFailure) { injectFailure = false; error("Injected preparation interruption") }
                }
            }
            try {
                if (injectFailure) {
                    assertTrue(backend.prepare().isFailure)
                    assertTrue(File(root, "rootfs/bin/sh").exists())
                    assertFalse(File(root, "rootfs/.terminal-environment-version").exists())
                    assertTrue(backend.status() is RootlessStatus.Error)
                    assertFalse(phases.contains(RootlessPhase.READY))
                }
                val sentinel = File(root, "rootfs/preserved-on-retry").apply { if (!exists()) writeText("keep image") }
                backend.prepare().getOrThrow()
                assertEquals(RootlessStatus.Ready, backend.status())
                assertEquals("keep image", sentinel.readText())
                assertEquals("# retain custom login\n", customLogin.readText())
                assertTrue(File(home, ".bashrc").isFile)
                assertFalse(File(home, ".bash_profile").exists()) // Must not shadow .profile on Arch.
                assertTrue(File(root, "rootfs/.terminal-environment-version").isFile)
                assertTrue(phases.indexOf(RootlessPhase.VERIFY) < phases.lastIndexOf(RootlessPhase.READY))
                val packageRuns = packages
                File(home, ".bashrc").writeText("# user shell configuration\n")
                backend.prepare().getOrThrow()
                assertEquals(packageRuns, packages) // Ready environments start offline without package work.
                assertEquals("# user shell configuration\n", File(home, ".bashrc").readText())
                val localeConfig = File(root, "rootfs/" + if (distribution == RootlessDistribution.ARCH) "etc/locale.conf" else "etc/default/locale")
                localeConfig.writeText("LANG=en_GB.UTF-8\n")
                File(root, "rootfs/etc/locale.gen").appendText("\nen_GB.UTF-8 UTF-8\n")
                val localtime = File(root, "rootfs/etc/localtime").toPath()
                java.nio.file.Files.delete(localtime)
                java.nio.file.Files.createSymbolicLink(localtime, java.nio.file.Paths.get("../usr/share/zoneinfo/Asia/Seoul"))
                backend.start().getOrThrow()
                val controller = requireNotNull(backend.sessionController())
                val session = controller.create("/bin/bash", "/root", emptyMap(), 24u, 80u).getOrThrow()
                val rootfs = File(root, "rootfs")
                val permissions = java.nio.file.Files.getPosixFilePermissions(rootfs.toPath())
                try {
                    rootfs.setWritable(true, true)
                    assertTrue(File(root, "rootfs/.terminal-environment-version").delete())
                } finally { java.nio.file.Files.setPosixFilePermissions(rootfs.toPath(), permissions) }
                assertTrue(backend.prepare().isFailure) // No mutation below a live PTY.
                assertTrue(controller.list().getOrThrow().any { it.id == session.id })
                assertEquals(packageRuns, packages)
                controller.terminate(session.id, 9).getOrThrow()
                // Restore marker only after the same full preparation/verification path succeeds.
                backend.prepare().getOrThrow()
                assertEquals(RootlessStatus.Ready, backend.status())
                assertEquals("LANG=en_GB.UTF-8\n", localeConfig.readText())
                assertEquals("../usr/share/zoneinfo/Asia/Seoul", java.nio.file.Files.readSymbolicLink(localtime).toString())
                // Repair failure must invalidate the previous marker, even if commands still exist.
                assertTrue(File(home, ".bashrc").delete())
                injectFailure = true
                assertTrue(backend.prepare().isFailure)
                assertFalse(File(root, "rootfs/.terminal-environment-version").exists())
                backend.prepare().getOrThrow()
                assertEquals(RootlessStatus.Ready, backend.status())
                android.util.Log.i("RootlessDefaultsTest", "${distribution.directory} PASSED")
            } finally {
                backend.stop(dev.terminal.runtime.api.StopMode.FORCE)
            }
        }
    }
}
