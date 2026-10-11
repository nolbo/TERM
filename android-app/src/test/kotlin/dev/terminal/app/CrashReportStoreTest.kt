package dev.terminal.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CrashReportStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val directory get() = File(temporary.root, "reports")

    @Test fun separateReportsSurviveReopeningAndKeepTheirOwnContents() {
        val store = CrashReportStore(directory)
        val first = store.record("first stack trace", "First failure", 100)
        val second = store.record("second stack trace", "Second failure", 200)
        val reopened = CrashReportStore(directory)
        assertEquals(listOf(second.id, first.id), reopened.reports().map { it.id })
        assertEquals(first, reopened.read(first.id))
        assertEquals(second, reopened.read(second.id))
    }

    @Test fun deletingOneReportPreservesOtherReportsAfterReopeningAndCanBeRepeated() {
        val store = CrashReportStore(directory)
        val first = store.record("first stack", "First", 100)
        val second = store.record("second stack", "Second", 200)
        store.delete(first.id)
        store.delete(first.id)
        val reopened = CrashReportStore(directory)
        assertNull(reopened.read(first.id))
        assertEquals(listOf(second), reopened.reports())
        assertEquals(second, reopened.read(second.id))
    }

    @Test fun deletionRejectsInvalidIdsWithoutChangingFilesOutsideHistory() {
        val store = CrashReportStore(directory)
        val report = store.record("valid", "Valid", 1)
        val outside = File(temporary.root, "outside.json").apply { writeText("outside") }
        for (id in listOf("../outside", "", "not-a-report")) {
            assertThrows(IllegalArgumentException::class.java) { store.delete(id) }
        }
        assertEquals("outside", outside.readText())
        assertEquals(report, store.read(report.id))
    }

    @Test fun failedDeletionIsReportedAndPreservesOtherReports() {
        val store = CrashReportStore(directory)
        val report = store.record("valid", "Valid", 1)
        val blocked = File(directory, "${UUID.randomUUID()}.json").apply { mkdir() }
        val child = File(blocked, "blocked").apply { writeText("cannot remove directory") }
        assertThrows(IOException::class.java) { store.delete(blocked.name.removeSuffix(".json")) }
        assertTrue(child.isFile)
        assertEquals(report, store.read(report.id))
    }

    @Test fun legacyImportIsIdempotentAndPreservesItsOriginalTimeAndStack() {
        val text = "시간: Wed Jan 01 00:00:00 UTC 2025\n스레드: main\n\njava.lang.IllegalStateException: legacy\n at fixture"
        val first = CrashReportStore(directory).importLegacy(text)
        val second = CrashReportStore(directory).importLegacy(text, 999)
        assertEquals(first, second)
        assertEquals(1735689600000L, first.createdAt)
        assertEquals("java.lang.IllegalStateException: legacy", first.summary)
        assertEquals(text, first.text)
        assertEquals(1, CrashReportStore(directory).reports().size)
    }

    @Test fun retentionKeepsTheNewestReportsWhenAnOlderReportArrivesLate() {
        val store = CrashReportStore(directory, retainedReports = 2)
        val newest = store.record("newest", "Newest", 300)
        val middle = store.record("middle", "Middle", 200)
        val oldest = store.record("oldest", "Oldest", 100)
        assertEquals(listOf(newest.id, middle.id), store.reports().map { it.id })
        assertNull(store.read(oldest.id))
    }

    @Test fun incompleteAndInvalidRecordsCannotHideValidReportsOrReadOutsideHistory() {
        val store = CrashReportStore(directory)
        val valid = store.record("valid", "Valid", 1)
        File(directory, "${UUID.randomUUID()}.json").writeText("incomplete JSON")
        File(directory, "interrupted.tmp").writeText("partial write")
        File(temporary.root, "outside.json").writeText("outside")
        assertEquals(listOf(valid), store.reports())
        assertNull(store.read("../outside"))
    }

    @Test fun concurrentWritersCommitDistinctReportsEvenWhenCreatingTheDirectory() {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val writes = (1..8).map { index -> executor.submit<CrashReport> {
                start.await()
                CrashReportStore(directory).record("trace $index", "Failure $index", index.toLong())
            } }
            start.countDown()
            val saved = writes.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(saved.map { it.id }.toSet(), CrashReportStore(directory).reports().map { it.id }.toSet())
            saved.forEach { assertEquals(it, CrashReportStore(directory).read(it.id)) }
        } finally { executor.shutdownNow() }
    }

    @Test fun excessivelyLongReportsAreBoundedAndStillReadableAfterRestart() {
        val store = CrashReportStore(directory)
        val text = "😀".repeat(50_000)
        val report = store.record(text, "Large failure", 1)
        assertTrue(report.text.startsWith(text.take(1000)))
        assertTrue(report.text.length <= 65_536)
        assertTrue(report.text.endsWith("[Report truncated]"))
        assertEquals(report, CrashReportStore(directory).read(report.id))
    }
}
