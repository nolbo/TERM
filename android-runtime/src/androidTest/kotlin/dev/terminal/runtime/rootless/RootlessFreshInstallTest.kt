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
            require(!pathKernel || statKernel) { "Path experiment requires the verified stat experiment payload" }
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
            RootlessEnvironmentSetup(context, layout, proot, guestLoader, reporter, statKernel, pathKernel).prepare()
            RootlessEnvironmentProfile.validate(layout).getOrThrow()
            val check = runRootlessSetupCommand(layout, proot, guestLoader, listOf("/bin/sh", "-c", """
                set -eu
                test "${'$'}(id -u)" = 0
                work=${'$'}(mktemp -d)
                trap 'rm -rf "${'$'}work"' EXIT
                printf 'native link\n' > "${'$'}work/a"
                ln "${'$'}work/a" "${'$'}work/b"
                test ! -L "${'$'}work/b"
                test "${'$'}(stat -c %h "${'$'}work/b")" = 2
                rm "${'$'}work/a"
                test "${'$'}(cat "${'$'}work/b")" = 'native link'
                printf 'PASS fresh distribution native links\n'
            """.trimIndent()), statKernelAcceleration = statKernel, pathKernelAcceleration = pathKernel)
            assertEquals(check.output, 0, check.exitCode)
            assertTrue(check.output.contains("PASS fresh distribution native links"))
            Log.i("RootlessFreshInstall", "${distribution.name}: VERIFIED")
        } finally {
            deleteRootlessTree(temporary)
        }
    }
}
