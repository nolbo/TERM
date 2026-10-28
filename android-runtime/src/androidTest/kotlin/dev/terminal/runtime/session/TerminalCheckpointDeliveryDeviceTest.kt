package dev.terminal.runtime.session

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.emulator.TerminalCheckpoint
import dev.terminal.emulator.TextStyle
import dev.terminal.runtime.ipc.ITerminalStateListener
import java.io.DataInputStream
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalCheckpointDeliveryDeviceTest {
    @Test fun delayedDeliveryUsesFrozenStateAndKeepsCheckpointOutputResizeExitOrdered() = verifyDelivery(CheckpointBudget())
    @Test fun capturePressurePreservesFullStateThroughDiskFallback() = verifyDelivery(CheckpointBudget(0))
    @Test fun largeStyledHistorySurvivesCaptureAndAndroidDeserialization() = verifyDelivery(CheckpointBudget(), true)

    @Test fun outputGapQueuesParserResetBeforeResumedBytes() = verifyDelivery(CheckpointBudget(), gap = true)

    private fun verifyDelivery(budget: CheckpointBudget, large: Boolean = false, gap: Boolean = false) = runBlocking {
        val cache = Files.createTempDirectory(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.toPath(), "checkpoint-test-").toFile()
        val worker = Executors.newSingleThreadExecutor()
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        worker.execute { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val calls = ConcurrentLinkedQueue<String>()
        val failures = ConcurrentLinkedQueue<Exception>()
        val done = CountDownLatch(1)
        val listener = object : ITerminalStateListener.Stub() {
            override fun onCheckpoint(state: ParcelFileDescriptor) {
                ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(state.fileDescriptor)).use { stream ->
                    val input = DataInputStream(stream)
                    assertFalse(input.readBoolean())
                    val checkpoint = TerminalCheckpoint.read(input)
                    val text = checkpoint.screen.transcriptText
                    assertTrue(text.contains("BEFORE"))
                    assertFalse(text.contains("AFTER"))
                    if (large) {
                        assertTrue(checkpoint.screen.activeTranscriptRows >= 9_000)
                        assertEquals(1, TextStyle.decodeForeColor(checkpoint.screen.getStyleAt(-1, 8)))
                    }
                    calls.add("checkpoint")
                }
            }
            override fun onOutput(bytes: ByteArray) { calls.add(bytes.decodeToString()) }
            override fun onResize(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) { calls.add("resize:$columns") }
            override fun onExited(code: Int) { calls.add("exit:$code"); done.countDown() }
            override fun onError(message: String) { throw AssertionError(message) }
        }
        val sink = QueuedTerminalSink(cache, listener, worker, budget) { failures.add(it); done.countDown() }
        try {
            val state = RuntimeTerminalState(if (large) 10_000 else 100)
            state.create("a", if (large) 200 else 40, 8); state.create("b", 40, 8)
            if (large) state.output("a", "history \u001b[31mred\u001b[0m\r\n".repeat(10_000).toByteArray())
            state.output("a", ("BEFORE" + if (gap) "\u001b]0;partial" else "").toByteArray())
            state.withSession("a") { state.subscribe("a", sink) }
            assertTrue("No named checkpoint may remain", cache.listFiles()!!.isEmpty())
            state.withSession("a") {
                if (gap) state.outputGap("a")
                state.output("a", "AFTER".toByteArray())
                state.resize("a", 41, 8)
                state.exited("a", 7)
            }
            state.withSession("b") { state.output("b", "SIBLING".toByteArray()) }
            assertTrue(calls.isEmpty())
            release.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertTrue(failures.toString(), failures.isEmpty())
            assertEquals((if (gap) listOf("checkpoint", "checkpoint") else listOf("checkpoint")) +
                listOf("AFTER", "resize:41", "exit:7"), calls.toList())
            assertTrue("Checkpoint files must remain unlinked", cache.listFiles()!!.isEmpty())
        } finally {
            release.countDown(); sink.close(); worker.shutdownNow()
            worker.awaitTermination(2, TimeUnit.SECONDS)
            cache.deleteRecursively()
        }
    }
}
