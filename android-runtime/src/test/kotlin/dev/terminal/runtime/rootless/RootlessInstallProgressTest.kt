package dev.terminal.runtime.rootless

import org.junit.Assert.*
import org.junit.Test

class RootlessInstallProgressTest {
    @Test fun retainsLatestSnapshotForReconnectWhileThrottlingFrequentUpdates() {
        var time = 1000L
        val tracker = RootlessProgressTracker { time }
        assertTrue(tracker.record(RootlessProgressUpdate(RootlessPhase.DOWNLOAD, "start", 0f)))
        time += 10
        assertFalse(tracker.record(RootlessProgressUpdate(RootlessPhase.DOWNLOAD, "bytes", .2f, 20, 100)))
        assertEquals(.2f, tracker.latest!!.update.fraction)
        assertEquals(1000L, tracker.latest!!.startedAtElapsedMs)
        assertEquals(1010L, tracker.latest!!.updatedAtElapsedMs)
        time += 10
        assertTrue(tracker.record(RootlessProgressUpdate(RootlessPhase.CHECKSUM, "verify")))
        assertNull(tracker.latest!!.update.fraction)
        assertEquals(1020L, tracker.latest!!.phaseStartedAtElapsedMs)
        tracker.finish()
        assertEquals(1020L, tracker.latest!!.finishedAtElapsedMs)
        tracker.reset()
        assertNull(tracker.latest)
    }

    @Test fun parsesAptDownloadAndInstallationWithoutTreating100PercentAsReady() {
        val events = mutableListOf<RootlessProgressUpdate>()
        val parser = RootlessPackageProgress(RootlessPhase.PACKAGES, reporter(events))
        parser.line("TERMINAL_SETUP_PHASE:PACKAGE_INDEX")
        parser.line("dlstatus:1:50.0:Downloading file 1 of 2")
        assertEquals(RootlessPhase.PACKAGE_INDEX, events.last().phase)
        assertEquals(.5f, events.last().fraction)
        parser.line("TERMINAL_SETUP_PHASE:PACKAGE_INSTALL")
        parser.line("dlstatus:1:100:Downloading file 1 of 1")
        assertEquals(RootlessPhase.PACKAGE_DOWNLOAD, events.last().phase)
        parser.line("pmstatus:libc6:arm64:80:Configuring libc6:arm64")
        assertEquals("libc6:arm64 · 설정 적용", events.last().detail)
        assertEquals(.8f, events.last().fraction)
        parser.line("pmstatus:bash:100:Installed bash")
        assertEquals(RootlessPhase.PACKAGE_INSTALL, events.last().phase)
        assertEquals(1f, events.last().fraction)
        parser.line("TERMINAL_SETUP_PHASE:PACKAGE_UPGRADE")
        parser.line("pmstatus:bash:5:Preparing bash")
        assertEquals(RootlessPhase.PACKAGE_UPGRADE, events.last().phase)
        assertEquals(.05f, events.last().fraction)
        parser.line("TERMINAL_SETUP_PHASE:READY") // Guest output cannot declare readiness.
        assertFalse(events.any { it.phase == RootlessPhase.READY })
    }

    @Test fun archUsesObservedStagesAndNeverInventsAnOverallPercentage() {
        val events = mutableListOf<RootlessProgressUpdate>()
        val parser = RootlessPackageProgress(RootlessPhase.PACKAGES, reporter(events))
        listOf(":: Synchronizing package databases...", ":: Starting full system upgrade...",
            ":: Retrieving packages...", "glibc-2.43-1-aarch64 downloading...", "checking keys in keyring...",
            ":: Processing package changes...", "upgrading glibc...", ":: Running post-transaction hooks...").forEach(parser::line)
        assertEquals(listOf(RootlessPhase.PACKAGE_INDEX, RootlessPhase.PACKAGE_PLAN, RootlessPhase.PACKAGE_DOWNLOAD,
            RootlessPhase.PACKAGE_DOWNLOAD, RootlessPhase.PACKAGE_CHECK, RootlessPhase.PACKAGE_INSTALL,
            RootlessPhase.PACKAGE_INSTALL, RootlessPhase.PACKAGE_HOOKS), events.map { it.phase })
        assertTrue(events.all { it.fraction == null })
        val size = events.size
        parser.line("arbitrary /data/user/0/private/path")
        parser.line("TERMINAL_SETUP_PHASE:invalid")
        assertEquals(size, events.size)
    }

    @Test fun pipeLinesHandleChunkBoundariesCarriageReturnsAndOversizedOutput() {
        val lines = mutableListOf<String>()
        val framer = RootlessOutputLines(lines::add)
        for (chunk in listOf("pmsta", "tus:bash:10:Preparing\r", "x".repeat(5000), "\nfinal")) {
            framer.accept(chunk.toCharArray(), chunk.length)
        }
        framer.flush()
        assertEquals(listOf("pmstatus:bash:10:Preparing", "final"), lines)
    }

    private fun reporter(events: MutableList<RootlessProgressUpdate>) = object : RootlessProgressReporter {
        override fun report(phase: RootlessPhase, detail: String) { events += RootlessProgressUpdate(phase, detail) }
        override fun update(progress: RootlessProgressUpdate) { events += progress }
    }
}
