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

    @Test fun entryOptionsReachOnlyExplicitHostProcesses() = runBlocking {
        val fakeProot = executable("printf '%s:%s\\n' \"\${PROOT_TCGETS_DIRECT-unset}\" \"\${PROOT_ID_ENTRY-unset}\"; printf '%s\\n' \"\$@\"")
        val current = layout()
        for ((tcgets, identity) in listOf(false to false, true to false, false to true, true to true)) {
            val result = runRootlessSetupCommand(current, fakeProot, fakeProot, listOf("/bin/true"),
                tcgetsDirect = tcgets, idEntry = identity)
            assertEquals(0, result.exitCode)
            assertTrue(result.output.startsWith("${if (tcgets) "1" else "unset"}:${if (identity) "1" else "unset"}\n"))
            assertFalse(result.output.contains("PROOT_TCGETS_DIRECT="))
            assertFalse(result.output.contains("PROOT_ID_ENTRY="))
        }
    }

    @Test fun notificationThreadModeDoesNotLeakIntoOrdinarySetup() = runBlocking {
        val fakeProot = executable("printf '%s:%s' \"\${PROOT_OPEN_NOTIFY-unset}\" \"\${PROOT_OPEN_NOTIFY_THREAD-unset}\"")
        val current = layout()
        val accelerated = runRootlessSetupCommand(current, fakeProot, fakeProot, listOf("/bin/true"),
            openNotifyThread = true)
        assertEquals(0, accelerated.exitCode)
        assertEquals("1:1", accelerated.output)
        val ordinary = runRootlessSetupCommand(current, fakeProot, fakeProot, listOf("/bin/true"))
        assertEquals(0, ordinary.exitCode)
        assertEquals("unset:unset", ordinary.output)
    }

    @Test fun fastfdDsoIsBoundOnlyForExplicitGuestSetup() = runBlocking {
        val fakeProot = executable("printf '%s:%s:%s\\n' \"\${PROOT_FASTSTAT-unset}\" \"\${PROOT_FASTFD-unset}\" \"\${LD_PRELOAD-unset}\"; printf '%s\\n' \"\$@\"")
        val library = temporary.newFile("libproot-faststat.so")
        val current = layout()
        val accelerated = runRootlessSetupCommand(current, fakeProot, fakeProot,
            listOf("/bin/true"), fastfdLibrary = library)
        assertEquals(0, accelerated.exitCode)
        assertTrue(accelerated.output.startsWith("2:1:unset\n"))
        assertTrue(accelerated.output.contains("${library.absolutePath}:/usr/local/lib/terminal-faststat.so\n"))
        assertTrue(accelerated.output.contains("LD_PRELOAD=/usr/local/lib/terminal-faststat.so\n"))
        assertTrue(accelerated.output.contains("PROOT_FASTFD=1\n"))
        assertTrue(accelerated.output.contains("PROOT_FASTSTAT_PATH=0\n"))
        val ordinary = runRootlessSetupCommand(current, fakeProot, fakeProot, listOf("/bin/true"))
        assertTrue(ordinary.output.startsWith("unset:unset:unset\n"))
        assertFalse(ordinary.output.contains("terminal-faststat.so"))
        assertFalse(ordinary.output.contains("PROOT_FASTFD="))
        assertFalse(ordinary.output.contains("PROOT_FASTSTAT_PATH="))
    }

    @Test fun fastOpenRequiresExplicitDsoAndSeparatesHostAndGuestOptions() = runBlocking {
        val fakeProot = executable("printf '%s:%s\\n' \"\${PROOT_FASTPATH-unset}\" \"\${PROOT_FASTOPEN-unset}\"; printf '%s\\n' \"\$@\"")
        val current = layout()
        val library = temporary.newFile("open-dso.so")
        val enabled = runRootlessSetupCommand(current, fakeProot, fakeProot,
            listOf("/bin/true"), fastfdLibrary = library, fastOpen = true, fastCreate = true, fastUnlink = true)
        assertEquals(0, enabled.exitCode)
        assertTrue(enabled.output.startsWith("1:unset\n"))
        assertTrue(enabled.output.contains("PROOT_FASTOPEN=1\n"))
        assertTrue(enabled.output.contains("PROOT_FASTCREATE=1\n"))
        assertTrue(enabled.output.contains("PROOT_FASTUNLINK=1\n"))
        val disabled = runRootlessSetupCommand(current, fakeProot, fakeProot,
            listOf("/bin/true"), fastfdLibrary = library)
        assertTrue(disabled.output.startsWith("unset:unset\n"))
        assertFalse(disabled.output.contains("PROOT_FASTOPEN="))
        assertFalse(disabled.output.contains("PROOT_FASTCREATE="))
        assertFalse(disabled.output.contains("PROOT_FASTUNLINK="))
        assertTrue(runCatching { runRootlessSetupCommand(current, fakeProot, fakeProot,
            listOf("/bin/true"), fastUnlink = true) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { runRootlessSetupCommand(current, fakeProot, fakeProot,
            listOf("/bin/true"), fastCreate = true) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { runRootlessSetupCommand(current, fakeProot, fakeProot,
            listOf("/bin/true"), fastOpen = true) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { runRootlessSetupCommand(current, fakeProot, fakeProot,
            listOf("/bin/true"), fastfdLibrary = library, fastOpen = true,
            openNotifyThread = true) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun sharedStatSeparatesHostAndGuestOptionsAndRequiresDso() = runBlocking {
        val fakeProot = executable("printf '%s:%s\\n' \"\${PROOT_FASTPATH-unset}\" \"\${PROOT_FASTSTAT_SHARED-unset}\"; printf '%s\\n' \"\$@\"")
        val current = layout()
        val library = temporary.newFile("stat-dso.so")
        val enabled = runRootlessSetupCommand(current, fakeProot, fakeProot,
            listOf("/bin/true"), fastfdLibrary = library, sharedStat = true)
        assertEquals(0, enabled.exitCode)
        assertTrue(enabled.output.startsWith("1:unset\n"))
        assertTrue(enabled.output.contains("PROOT_FASTSTAT_SHARED=1\n"))
        val ordinary = runRootlessSetupCommand(current, fakeProot, fakeProot,
            listOf("/bin/true"), fastfdLibrary = library)
        assertTrue(ordinary.output.startsWith("unset:unset\n"))
        assertFalse(ordinary.output.contains("PROOT_FASTSTAT_SHARED="))
        assertTrue(runCatching { runRootlessSetupCommand(current, fakeProot, fakeProot,
            listOf("/bin/true"), sharedStat = true) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { runRootlessSetupCommand(current, fakeProot, fakeProot,
            listOf("/bin/true"), sharedStat = true, fastfdLibrary = library,
            openNotifyThread = true) }.exceptionOrNull() is IllegalArgumentException)
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
