package dev.terminal.runtime.rootless

import java.io.File
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootlessSetupCommandTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun layout() = RootlessDebianLayout(File(temporary.root, "rootless/debian")).apply { createHostDirectories() }
    private fun executable(script: String) = temporary.newFile().apply { writeText("#!/bin/sh\n$script\n"); setExecutable(true) }

    @Test fun experimentalStatModeReachesOnlyExplicitlyEnabledSetupProcesses() = runBlocking {
        val fakeProot = executable("printf '%s:%s:%s:%s:%s' \"\${PROOT_STAT_ENTRY-unset}\" \"\${PROOT_STAT_KERNEL-unset}\" \"\${PROOT_PATH_KERNEL-unset}\" \"\${PROOT_MUTATION_ENTRY-unset}\" \"\${PROOT_FSTAT_ENTRY-unset}\"")
        val current = layout()
        val accelerated = runRootlessSetupCommand(current, fakeProot, fakeProot, listOf("/bin/true"),
            statKernelAcceleration = true, pathKernelAcceleration = true, mutationEntryAcceleration = true, fstatEntryAcceleration = true)
        assertEquals(0, accelerated.exitCode)
        assertEquals("1:1:1:1:1", accelerated.output)
        val ordinary = runRootlessSetupCommand(current, fakeProot, fakeProot, listOf("/bin/true"))
        assertEquals(0, ordinary.exitCode)
        assertEquals("unset:unset:unset:unset:unset", ordinary.output)
    }

    @Test fun packageOutputIsBoundedAndExitFailuresRemainVisible() = runBlocking {
        val fakeProot = executable("i=0; while [ \$i -lt 2000 ]; do echo '012345678901234567890123456789'; i=\$((i+1)); done; echo decisive-error; exit 17")
        val result = runRootlessSetupCommand(layout(), fakeProot, fakeProot, listOf("/bin/true"), 10_000)
        assertEquals(17, result.exitCode)
        assertTrue(result.output.length <= 8192)
        assertTrue(result.output.endsWith("decisive-error\n"))
    }

    @Test fun timeoutKillsTheOwnedProcessAndReleasesItsOutputReader() = runBlocking {
        val fakeProot = executable("exec /bin/sleep 60")
        val start = System.nanoTime()
        val result = runCatching { runRootlessSetupCommand(layout(), fakeProot, fakeProot, listOf("/bin/true"), 100) }
        assertTrue(result.exceptionOrNull() is TimeoutCancellationException)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000)
    }

    @Test fun firstPackageFailureSurvivesLaterVerboseOutputAndLiveLinesArrive() = runBlocking {
        val fakeProot = executable("echo 'sed: cannot rename configuration'; echo 'dpkg: error processing package example'; i=0; while [ \$i -lt 1000 ]; do echo 'later harmless output'; i=\$((i+1)); done; exit 1")
        val lines = mutableListOf<String>()
        val result = runRootlessSetupCommand(layout(), fakeProot, fakeProot, listOf("/bin/true"), onOutputLine = lines::add)
        assertEquals(1, result.exitCode)
        assertTrue(result.errorContext.contains("sed: cannot rename configuration"))
        assertTrue(result.errorContext.contains("dpkg: error processing package example"))
        assertFalse(result.output.contains("cannot rename"))
        assertTrue(lines.any { it.startsWith("dpkg: error") })
    }
}
