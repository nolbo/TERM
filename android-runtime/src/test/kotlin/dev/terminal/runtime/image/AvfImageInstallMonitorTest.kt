package dev.terminal.runtime.image

import androidx.work.Data
import androidx.work.WorkInfo
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class AvfImageInstallMonitorTest {
    private fun data(phase: AvfImageInstallPhase, total: Long = 100) = Data.Builder()
        .putString(AvfImageInstallMonitor.KEY_PHASE, phase.name)
        .putInt(AvfImageInstallMonitor.KEY_ARTIFACT, 3)
        .putLong(AvfImageInstallMonitor.KEY_BYTES, 40)
        .putLong(AvfImageInstallMonitor.KEY_TOTAL, total)
        .putLong(AvfImageInstallMonitor.KEY_STARTED, 1000)
        .putLong(AvfImageInstallMonitor.KEY_UPDATED, 5000).build()

    private fun work(state: WorkInfo.State, progress: Data = Data.EMPTY, output: Data = Data.EMPTY, attempts: Int = 0) =
        WorkInfo(UUID.randomUUID(), state, setOf("${AvfImageInstallMonitor.STORAGE_TAG}test-storage"), output, progress, attempts)

    @Test fun waitingAndRetryAreDistinctAndDoNotInventProgress() {
        val waiting = AvfImageInstallMonitor.snapshot(work(WorkInfo.State.ENQUEUED))
        assertEquals(AvfImageInstallState.WAITING, waiting.state)
        assertTrue(waiting.active)
        assertNull(waiting.fraction)
        assertNull(waiting.startedAtElapsedMs)
        assertEquals("test-storage", waiting.storageId)
        assertEquals(AvfImageInstallState.RETRYING,
            AvfImageInstallMonitor.snapshot(work(WorkInfo.State.ENQUEUED, attempts = 1)).state)
    }

    @Test fun fractionAppliesOnlyToKnownCurrentDownloadAndNeverVerification() {
        val downloading = AvfImageInstallMonitor.snapshot(work(WorkInfo.State.RUNNING, data(AvfImageInstallPhase.DOWNLOAD)))
        assertEquals(.4f, downloading.fraction!!, .001f)
        assertEquals(3, downloading.artifactIndex)
        assertEquals(1000L, downloading.startedAtElapsedMs)
        assertNull(AvfImageInstallMonitor.snapshot(work(WorkInfo.State.RUNNING, data(AvfImageInstallPhase.DOWNLOAD, -1))).fraction)
        assertNull(AvfImageInstallMonitor.snapshot(work(WorkInfo.State.RUNNING, data(AvfImageInstallPhase.VERIFY))).fraction)
    }

    @Test fun finishedWorkRetainsOutputSnapshotAfterRunningProgressIsCleared() {
        val done = AvfImageInstallMonitor.snapshot(work(WorkInfo.State.SUCCEEDED,
            output = data(AvfImageInstallPhase.COMPLETE)))
        assertEquals(AvfImageInstallPhase.COMPLETE, done.phase)
        assertFalse(done.active)
        assertEquals(5000L, done.updatedAtElapsedMs)
        val failedData = Data.Builder().putAll(data(AvfImageInstallPhase.VERIFY))
            .putString(DebianImageDownloadWorker.KEY_ERROR, "Checksum mismatch").build()
        val failed = AvfImageInstallMonitor.snapshot(work(WorkInfo.State.FAILED, output = failedData))
        assertEquals(AvfImageInstallPhase.VERIFY, failed.phase)
        assertEquals("Checksum mismatch", failed.error)
        assertFalse(failed.active)
        assertFalse(AvfImageInstallMonitor.snapshot(work(WorkInfo.State.CANCELLED)).active)
    }

    @Test fun latestResultUsesRequestTimeEvenWhenElapsedClockResetsAcrossReboot() {
        fun previous(at: Long, elapsed: Long) = WorkInfo(UUID.randomUUID(), WorkInfo.State.FAILED,
            setOf("${AvfImageInstallMonitor.STORAGE_TAG}same-storage", "${AvfImageInstallMonitor.REQUESTED_TAG}$at"),
            Data.Builder().putLong(AvfImageInstallMonitor.KEY_STARTED, elapsed).build())
        val old = previous(1000, 900_000)
        val recent = previous(2000, 100)
        assertEquals(recent.id.toString(), AvfImageInstallMonitor.snapshots(listOf(old, recent)).single().workId)
        val waiting = WorkInfo(UUID.randomUUID(), WorkInfo.State.ENQUEUED,
            setOf("${AvfImageInstallMonitor.STORAGE_TAG}same-storage", "${AvfImageInstallMonitor.REQUESTED_TAG}3000"))
        assertEquals(waiting.id.toString(), AvfImageInstallMonitor.snapshots(listOf(old, recent, waiting)).single().workId)
    }

    @Test fun platformFailureRetainsTheConstructorAndClassThroughPersistenceAndObservation() {
        val error = "AVF 맞춤 이미지 API 오류: ".repeat(20) +
            "No direct method <init>(Ljava/lang/String;IIIIILjava/lang/String;Ljava/lang/String;ZLjava/lang/String;)V " +
            "in class Landroid/system/virtualmachine/VirtualMachineCustomImageConfig\$SharedPath;"
        assertTrue(error.length > 240)
        val output = DebianImageDownloadWorker.failureData(data(AvfImageInstallPhase.BOOT), error)
        val failed = AvfImageInstallMonitor.snapshot(work(WorkInfo.State.FAILED, output = output))
        assertEquals(error, failed.error)
        assertEquals(AvfImageInstallPhase.BOOT, failed.phase)
        assertEquals(5000L, failed.updatedAtElapsedMs)
    }

    @Test fun oversizedUnicodeFailureStillFitsWorkManagerDataAndMarksTruncation() {
        val output = DebianImageDownloadWorker.failureData(data(AvfImageInstallPhase.BOOT), "가".repeat(20_000))
        val failed = AvfImageInstallMonitor.snapshot(work(WorkInfo.State.FAILED, output = output))
        assertEquals(DebianImageDownloadWorker.MAX_ERROR_CHARACTERS, failed.error!!.length)
        assertTrue(failed.error.endsWith("…"))
        assertEquals(AvfImageInstallPhase.BOOT, failed.phase)
    }
}
