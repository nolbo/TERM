package dev.terminal.app

import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.protocol.SessionEvent
import dev.terminal.runtime.native.ApkNativeExecutableLoader
import dev.terminal.runtime.rootless.RootlessDebianLayout
import dev.terminal.runtime.rootless.RootlessDistribution
import dev.terminal.runtime.rootless.RootlessSessionController
import dev.terminal.runtime.session.SessionStorage
import dev.terminal.runtime.storage.SharedFolder
import dev.terminal.runtime.storage.SharedFolderBinding
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootlessSharedFoldersDeviceTest {
    /** Separate PTYs use installed images without touching user sessions or folder metadata. */
    @Test fun connectedFolderRemainsAccessibleWithNonDumpableOwner() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val layouts = RootlessDistribution.entries.map { distribution ->
            RootlessDebianLayout(SessionStorage.legacyRoot(context.filesDir, distribution.runtime), distribution)
        }.filter { it.validateInstalledRootfs().isSuccess }
        assumeTrue("Requires a provisioned Userspace Linux image; does not install one", layouts.isNotEmpty())
        val executableLoader = ApkNativeExecutableLoader(context)
        val proot = executableLoader.resolvePackagedExecutable("proot").getOrThrow()
        val loader = executableLoader.resolvePackagedExecutable("proot-loader").getOrThrow()
        val directory = File(context.cacheDir, "shared-release-test-${UUID.randomUUID()}").apply { check(mkdir()) }.canonicalFile
        val folder = SharedFolder(UUID.randomUUID().toString(), "ReleaseShare", emptySet())
        val originalDumpable = Os.prctl(OsConstants.PR_GET_DUMPABLE, 0, 0, 0, 0)
        try {
            File(directory, "host.txt").writeText("host-shared-data")
            // Debug instrumentation otherwise hides failures involving a release owner's FD paths.
            Os.prctl(OsConstants.PR_SET_DUMPABLE, 0, 0, 0, 0)
            layouts.forEach { layout ->
                File(directory, "guest.txt").delete()
                val controller = RootlessSessionController(layout, proot, loader,
                    sharedFolders = { listOf(SharedFolderBinding(folder, directory)) })
                val events = Channel<SessionEvent>(Channel.UNLIMITED)
                val collector = launch(start = CoroutineStart.UNDISPATCHED) { controller.events.collect { events.send(it) } }
                try {
                    val session = controller.create("/bin/sh", "/root",
                        mapOf("DROID_LOGIN_SHELL" to "false", "PS1" to ""), 24u, 80u).getOrThrow()
                    val script = "stty -echo; set -e; cat '${folder.guestPath}/host.txt' > '${folder.guestPath}/guest.txt'; printf 'SHARED_DIRECTORY_OK\\n'; exit\n"
                    controller.write(session.id, script.toByteArray()).getOrThrow()
                    val output = ByteArrayOutputStream()
                    val exitCode = withTimeout(15_000) {
                        while (true) {
                            when (val event = events.receive()) {
                                is SessionEvent.Output -> if (event.sessionId == session.id) output.write(event.bytes)
                                is SessionEvent.Exited -> if (event.sessionId == session.id) return@withTimeout event.exitCode
                                else -> Unit
                            }
                        }
                        @Suppress("UNREACHABLE_CODE")
                        error("Session did not exit")
                    }
                    val transcript = output.toString("UTF-8")
                    assertEquals("${layout.distribution}: $transcript", 0, exitCode)
                    assertTrue(transcript, transcript.lineSequence().any { it.trim() == "SHARED_DIRECTORY_OK" })
                    assertFalse(transcript, transcript.contains("can't sanitize binding"))
                    assertEquals("host-shared-data", File(directory, "guest.txt").readText())
                    assertEquals(0, Os.prctl(OsConstants.PR_GET_DUMPABLE, 0, 0, 0, 0))
                } finally {
                    controller.close()
                    collector.cancelAndJoin()
                    events.close()
                }
            }
        } finally {
            Os.prctl(OsConstants.PR_SET_DUMPABLE, originalDumpable.toLong(), 0, 0, 0)
            directory.deleteRecursively()
        }
    }
}
