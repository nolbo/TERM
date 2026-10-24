package dev.terminal.runtime.lifecycle

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

class ConnectionLogStoreTest {
    @Test fun detailedFailuresKeepCauseTypesAndSourceLocationWithoutExceptionText() {
        val failure = IllegalStateException("/private/path?token=secret", java.io.IOException("terminal content"))
        failure.stackTrace = arrayOf(StackTraceElement("dev.terminal.runtime.vsock.Fixture", "connect", "Fixture.kt", 42))
        val details = connectionErrorDetails(failure)
        assertEquals("IllegalStateException:IOException", details["causes"])
        assertEquals("dev.terminal.runtime.vsock.Fixture.connect:42", details["error_at"])
        val line = connectionLogLine(42, 1, 1, "session.failed", "owner", null, "IllegalStateException", details)
        assertFalse(line.contains("private")); assertFalse(line.contains("secret")); assertFalse(line.contains("terminal content"))
    }

    @Test fun structuredDetailsCannotOverwriteCoreFieldsInjectLinesOrExceedTheStoredLineBound() {
        val line = connectionLogLine(42, 1, 1, "session.failed", "owner", null, "IOException",
            linkedMapOf("event" to "fake", "operation" to "write", "bytes" to "123", "unsafe" to "x\ntoken=secret") +
                (0..40).associate { "field_$it" to "x".repeat(160) })
        assertTrue(line.contains("event=session.failed")); assertFalse(line.contains("event=fake"))
        assertTrue(line.contains("operation=write bytes=123 unsafe=redacted"))
        assertFalse(line.contains("secret")); assertFalse(line.contains('\n'))
        assertTrue(line.length <= 768); assertTrue(line.endsWith("details_truncated=true"))
    }

    @Test fun inputErrorBurstsKeepTheFirstFailureAndReportSuppressedCountsWithBoundedKeys() {
        val bursts = DiagnosticBursts()
        assertEquals(0, bursts.admit("first", 0))
        assertNull(bursts.admit("first", 1))
        assertNull(bursts.admit("first", 2))
        assertEquals(2, bursts.admit("first", 1_000_000_000))
        assertEquals(0, bursts.admit("second", 1_000_000_000))
        repeat(128) { bursts.admit("other-$it", 1_000_000_000) }
        assertEquals(0, bursts.admit("first", 1_000_000_001))
    }

    private fun fixture(work: (File, AtomicLong) -> Unit) {
        val root = Files.createTempDirectory("diagnostic-log-test").toFile()
        try { work(root, AtomicLong(1_000_000)) } finally { root.deleteRecursively() }
    }

    @Test fun existingRecordsRemainReadableAndDisabledRecordingDoesNotBackfillOnEnable() = fixture { root, clock ->
        File(root, "connection-events.log").writeText("legacy-record\n")
        val first = ConnectionLogStore(root, clock::get)
        assertTrue(first.snapshot().enabled)
        assertEquals(listOf("legacy-record"), first.snapshot().entries)
        clock.set(2_000_000); first.setEnabled(false)
        val otherProcess = ConnectionLogStore(root, clock::get)
        assertFalse(otherProcess.snapshot().enabled)
        assertFalse(otherProcess.append("disabled", 2_100_000))
        clock.set(3_000_000); first.setEnabled(true)
        assertFalse(otherProcess.append("queued-while-disabled", 2_500_000))
        clock.set(4_000_000)
        assertTrue(otherProcess.append("fresh", 4_000_000))
        assertEquals(listOf("legacy-record", "fresh"), first.snapshot().entries)
    }

    @Test fun clearRejectsQueuedOldEventsAcrossStoreInstancesWithoutDisablingRecording() = fixture { root, clock ->
        val writer = ConnectionLogStore(root, clock::get)
        assertTrue(writer.append("before", clock.get()))
        val viewer = ConnectionLogStore(root, clock::get)
        clock.set(2_000_000); viewer.clear()
        assertTrue(viewer.snapshot().enabled)
        assertTrue(viewer.snapshot().entries.isEmpty())
        assertFalse(writer.append("queued-before-clear", 1_500_000))
        clock.set(3_000_000)
        assertTrue(writer.append("after", 3_000_000))
        assertEquals(listOf("after"), viewer.snapshot().entries)
    }

    @Test fun rebootDoesNotSuppressNewRecordsBehindAnOldUptimeBarrier() = fixture { root, clock ->
        val store = ConnectionLogStore(root, clock::get)
        store.clear()
        clock.set(10)
        assertTrue(ConnectionLogStore(root, clock::get).append("new-boot", 10))
        clock.set(20); store.setEnabled(false)
        clock.set(1)
        assertFalse(ConnectionLogStore(root, clock::get).append("still-disabled", 1))
        assertFalse(store.snapshot().enabled)
    }

    @Test(timeout = 20_000) fun bothRecordCountAndBytesAreBoundedAndCrashStagingIsReplaced() = fixture { root, clock ->
        val store = ConnectionLogStore(root, clock::get)
        File(root, ".connection-events.log.tmp").writeText("interrupted write")
        repeat(ConnectionLogStore.MAX_RECORDS + 5) { store.append("event-$it", clock.incrementAndGet()) }
        assertEquals(ConnectionLogStore.MAX_RECORDS, store.snapshot().entries.size)
        assertEquals("event-5", store.snapshot().entries.first())
        repeat(400) { store.append("$it:" + "x".repeat(700), clock.incrementAndGet()) }
        assertTrue(File(root, "connection-events.log").length() <= ConnectionLogStore.MAX_BYTES)
        assertTrue(store.snapshot().entries.last().startsWith("399:"))
        assertFalse(File(root, ".connection-events.log.tmp").exists())
    }

    @Test fun corruptConfigurationFailsClosedAndSymlinksCannotReadOrOverwriteOtherFiles() = fixture { root, clock ->
        val store = ConnectionLogStore(root, clock::get)
        File(root, "connection-events.settings").writeText("enabled=surprise\n")
        assertThrows(IllegalArgumentException::class.java) { store.append("must-not-save", clock.get()) }
        assertFalse(File(root, "connection-events.log").exists())
        File(root, "connection-events.settings").delete()
        val outside = File(root, "private").apply { writeText("private payload") }
        Files.createSymbolicLink(File(root, "connection-events.log").toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { store.snapshot() }
        assertThrows(IllegalArgumentException::class.java) { store.append("must-not-overwrite", clock.get()) }
        store.clear()
        assertEquals("private payload", outside.readText())
    }

    @Test fun oversizedLogsFailBoundedReadsAndRotateOnTheNextRecord() = fixture { root, clock ->
        val store = ConnectionLogStore(root, clock::get)
        File(root, "connection-events.log").writeBytes(ByteArray(ConnectionLogStore.MAX_BYTES + 1) { 65 })
        assertThrows(java.io.IOException::class.java) { store.snapshot() }
        assertTrue(store.append("recovered", clock.get()))
        assertEquals(listOf("recovered"), store.snapshot().entries)
    }

    @Test(timeout = 10_000) fun concurrentInstancesDoNotLoseEachOthersRecords() = fixture { root, clock ->
        val workers = Executors.newFixedThreadPool(4)
        try {
            (0..3).map { worker -> workers.submit {
                val store = ConnectionLogStore(root, clock::get)
                repeat(20) { store.append("$worker-$it", clock.incrementAndGet()) }
            } }.forEach { it.get(5, TimeUnit.SECONDS) }
            val entries = ConnectionLogStore(root, clock::get).snapshot().entries
            assertEquals(80, entries.size)
            assertEquals(80, entries.toSet().size)
        } finally { workers.shutdownNow() }
    }

    @Test(timeout = 10_000) fun fileLockAlsoSerializesAgainstAnotherProcess() = fixture { root, clock ->
        val child = ProcessBuilder("python3", "-c", """
            import fcntl,sys
            with open(sys.argv[1],'a') as lock:
                fcntl.lockf(lock,fcntl.LOCK_EX)
                print('LOCKED',flush=True)
                sys.stdin.readline()
        """.trimIndent(), File(root, ".connection-events.lock").path).redirectErrorStream(true).start()
        val workers = Executors.newFixedThreadPool(2)
        try {
            assertEquals("LOCKED", workers.submit<String?> { child.inputStream.bufferedReader().readLine() }.get(3, TimeUnit.SECONDS))
            val entered = CountDownLatch(1)
            val write = workers.submit { entered.countDown(); ConnectionLogStore(root, clock::get).setEnabled(false) }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            Thread.sleep(100)
            assertFalse("The external process still owns the file lock", write.isDone)
            child.outputStream.write("release\n".toByteArray()); child.outputStream.flush()
            write.get(3, TimeUnit.SECONDS)
            assertFalse(ConnectionLogStore(root, clock::get).snapshot().enabled)
        } finally { child.destroyForcibly(); workers.shutdownNow() }
    }

    @Test fun formatterExcludesMessagesPathsAndInjectedFields() {
        val line = connectionLogLine(42, 50_000_000, 7, "vm.start.failed", "/private/path", "one\ntoken=secret", "IOException")
        assertTrue(line.contains("time_ms=42 uptime_ms=50 pid=7 event=vm.start.failed"))
        assertTrue(line.contains("error=IOException"))
        assertFalse(line.contains("private")); assertFalse(line.contains("secret")); assertFalse(line.contains('\n'))
    }
}
