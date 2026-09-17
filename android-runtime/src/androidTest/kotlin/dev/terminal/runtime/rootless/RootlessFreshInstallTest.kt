package dev.terminal.runtime.rootless

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.runtime.native.ApkNativeExecutableLoader
import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in network installation in the library's standalone test APK, never the user's app. */
@RunWith(AndroidJUnit4::class)
class RootlessFreshInstallTest {
    @Test fun installsDistributionAndVerifiesNativeLinkEnvironment() = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("rootlessFreshInstall") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.terminal.runtime.test") {
            "Fresh-install tests must run in the standalone runtime test APK"
        }
        val distribution = RootlessDistribution.valueOf(
            (arguments.getString("rootlessDistribution") ?: "DEBIAN").uppercase(Locale.ROOT),
        )
        val temporary = Files.createTempDirectory(context.filesDir.toPath(), "fresh-rootless-")
        val layout = RootlessDebianLayout(File(temporary.toFile(), "rootless/${distribution.directory}"), distribution)
        var previousPhase: RootlessPhase? = null
        val reporter = RootlessProgressReporter { phase, _ ->
            if (phase != previousPhase) {
                Log.i("RootlessFreshInstall", "${distribution.name}: ${phase.name}")
                previousPhase = phase
            }
        }
        try {
            layout.createHostDirectories()
            val loader = ApkNativeExecutableLoader(context)
            val proot = loader.resolvePackagedExecutable("proot").getOrThrow()
            val guestLoader = loader.resolvePackagedExecutable("proot-loader").getOrThrow()
            val statKernel = arguments.getString("rootlessStatKernel") == "true"
            val pathKernel = arguments.getString("rootlessPathKernel") == "true"
            val mutationEntry = arguments.getString("rootlessMutationEntry") == "true"
            val fstatEntry = arguments.getString("rootlessFstatEntry") == "true"
            val openNotifyThread = arguments.getString("rootlessOpenNotifyThread") == "true"
            val tcgetsDirect = arguments.getString("rootlessTcgetsDirect") == "true"
            val idEntry = arguments.getString("rootlessIdEntry") == "true"
            require(!(tcgetsDirect || idEntry) || statKernel) { "Entry experiments require the verified experimental payload" }
            val fastUnlink = arguments.getString("rootlessFastUnlink") == "true"
            val fastCreate = arguments.getString("rootlessFastCreate") == "true"
            val fastOpen = arguments.getString("rootlessFastOpen") == "true"
            val sharedStat = arguments.getString("rootlessSharedStat") == "true"
            val fastfd = arguments.getString("rootlessFastfd") == "true"
            require(!sharedStat || fastfd) { "Shared stat requires fastfd" }
            require(!fastUnlink || fastOpen) { "Fast unlink requires fast open" }
            require(!fastCreate || fastOpen) { "Fast create requires fast open" }
            require(!fastOpen || fastfd) { "Fast open requires fastfd" }
            require(!fastfd || statKernel) { "Fastfd requires the verified experimental payload" }
            val fastfdLibrary = if (fastfd) File(context.applicationInfo.nativeLibraryDir, "libproot-faststat.so") else null
            fastfdLibrary?.let { library ->
                val expected = requireNotNull(arguments.getString("rootlessFastfdSha256"))
                require(expected.matches(Regex("[0-9a-f]{64}")))
                val actual = MessageDigest.getInstance("SHA-256").digest(library.readBytes())
                    .joinToString("") { "%02x".format(it) }
                assertEquals("Experimental guest DSO digest", expected, actual)
                Log.i("RootlessFreshInstall", "FASTFD=1 dso=$actual")
            }
            require(!pathKernel || statKernel) { "Path experiment requires the verified stat experiment payload" }
            require(!mutationEntry || statKernel) { "Mutation experiment requires the verified stat experiment payload" }
            require(!fstatEntry || statKernel) { "Fstat experiment requires the verified stat experiment payload" }
            require(!openNotifyThread || statKernel) { "Notification experiment requires the verified stat experiment payload" }
            if (statKernel) {
                val expected = requireNotNull(arguments.getString("rootlessProotSha256"))
                require(expected.matches(Regex("[0-9a-f]{64}")))
                val actual = MessageDigest.getInstance("SHA-256").digest(proot.readBytes())
                    .joinToString("") { "%02x".format(it) }
                assertEquals("Experimental PRoot digest", expected, actual)
                Log.i("RootlessFreshInstall", "STAT_KERNEL=1 proot=$actual")
            }
            RootlessBootstrapper(context, layout, reporter).installIfNeeded().getOrThrow()
            assertTrue(RootlessStorageFormat.usesNativeLinks(layout.rootfs.toPath()))
            val launch = buildRootlessLaunchSpec(layout, proot, guestLoader, "/bin/sh", "/", emptyMap())
            assertFalse(launch.arguments.contains("--link2symlink"))
            RootlessEnvironmentSetup(context, layout, proot, guestLoader, reporter,
                statKernel, pathKernel, mutationEntry, fstatEntry, openNotifyThread, fastfdLibrary, fastOpen, fastCreate, fastUnlink, tcgetsDirect, idEntry, sharedStat).prepare()
            RootlessEnvironmentProfile.validate(layout).getOrThrow()
            val check = runRootlessSetupCommand(layout, proot, guestLoader, listOf("/bin/sh", "-c", """
                set -eu
                ${if (fastfd) "exec 3>&2; export PROOT_FASTSTAT_REPORT=1 PROOT_FASTSTAT_REPORT_FD=3" else ":"}
                test "${'$'}(id -u)" = 0
                work=${'$'}(mktemp -d)
                trap 'rm -rf "${'$'}work"' EXIT
                printf 'native link\n' > "${'$'}work/a"
                ln "${'$'}work/a" "${'$'}work/b"
                test ! -L "${'$'}work/b"
                test "${'$'}(stat -c %h "${'$'}work/b")" = 2
                rm "${'$'}work/a"
                test "${'$'}(cat "${'$'}work/b")" = 'native link'
                ${if (fastOpen) "cat /etc/debian_version >/dev/null" else ":"}
                ${if (fastCreate || fastUnlink) "python3 -B -c 'import os,sys; os.chdir(sys.argv[1]); f=open(\"created\", \"wb\"); f.write(b\"created\"); f.close(); assert open(\"created\", \"rb\").read()==b\"created\"; os.unlink(\"created\")' \"${'$'}work\"" else ":"}
                printf 'PASS fresh distribution native links\n'
            """.trimIndent()), statKernelAcceleration = statKernel, pathKernelAcceleration = pathKernel,
                mutationEntryAcceleration = mutationEntry, fstatEntryAcceleration = fstatEntry,
                openNotifyThread = openNotifyThread, fastfdLibrary = fastfdLibrary, fastOpen = fastOpen, fastCreate = fastCreate, fastUnlink = fastUnlink,
                tcgetsDirect = tcgetsDirect, idEntry = idEntry, sharedStat = sharedStat)
            assertEquals(check.output, 0, check.exitCode)
            assertTrue(check.output.contains("PASS fresh distribution native links"))
            if (sharedStat) assertTrue("No accelerated path stat observed: ${check.output}",
                Regex("\"hits\":([1-9][0-9]*)").containsMatchIn(check.output))
            if (fastfd) assertTrue("No accelerated fstat observed: ${check.output}",
                Regex("\"fd_hits\":([1-9][0-9]*)").containsMatchIn(check.output))
            if (fastOpen) assertTrue("No accelerated open observed: ${check.output}",
                Regex("\"open_hits\":([1-9][0-9]*)").containsMatchIn(check.output))
            if (fastCreate) assertTrue("No accelerated creation observed: ${check.output}",
                Regex("\"create_hits\":([1-9][0-9]*)").containsMatchIn(check.output))
            if (fastUnlink) assertTrue("No accelerated unlink observed: ${check.output}",
                Regex("\"unlink_hits\":([1-9][0-9]*)").containsMatchIn(check.output))
            if (dev.terminal.runtime.BuildConfig.ROOTLESS_ACCELERATION) {
                val controller = RootlessSessionController(layout, proot, guestLoader)
                try {
                    val output = StringBuilder()
                    val observed = async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(30_000) {
                            controller.events.first { event ->
                                if (event is dev.terminal.protocol.SessionEvent.Output) output.append(event.bytes.toString(Charsets.UTF_8))
                                output.contains("PASS accelerated PTY")
                            }
                        }
                    }
                    val command = listOf("/usr/bin/python3", "-c",
                        "import ctypes,os; assert os.stat('/etc/debian_version').st_size>0; " +
                            "assert ctypes.CDLL(None).proot_faststat_hits()>0; print('PASS accelerated PTY')")
                    controller.create("/bin/sh", "/root", mapOf(
                        dev.terminal.runtime.session.SessionCommand.KEY to dev.terminal.runtime.session.SessionCommand.encode(command)),
                        24u, 80u).getOrThrow()
                    observed.await()
                } finally {
                    controller.close()
                }
            }
            Log.i("RootlessFreshInstall", "${distribution.name}: VERIFIED")
        } finally {
            deleteRootlessTree(temporary)
        }
    }
}
