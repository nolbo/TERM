package dev.terminal.runtime.session

import dev.terminal.emulator.TerminalCheckpoint
import dev.terminal.emulator.TerminalEmulator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TerminalCheckpointSnapshotTest {
    private class Capture(private val budget: CheckpointBudget = CheckpointBudget()) : RuntimeTerminalState.Sink {
        lateinit var snapshot: TerminalCheckpointSnapshot
        override fun checkpoint(emulator: TerminalEmulator, viewport: RuntimeTerminalState.Viewport?) {
            snapshot = TerminalCheckpointSnapshot.capture(emulator, viewport, budget)
        }
        override fun output(bytes: ByteArray) = Unit
        override fun resized(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) = Unit
        override fun exited(code: Int) = Unit
    }

    @Test fun compressionOfFrozenStateDoesNotBlockLiveOutputOrIncludeLaterChanges() = runBlocking {
        val state = RuntimeTerminalState(100)
        state.create("a", 40, 8); state.create("b", 40, 8)
        state.output("a", "BEFORE\u001b[4;".toByteArray())
        val capture = Capture()
        state.withSession("a") { state.subscribe("a", capture); state.unsubscribe("a", capture) }
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val bytes = ByteArrayOutputStream()
        val writer = async(Dispatchers.IO) {
            capture.snapshot.writeTo(object : OutputStream() {
                override fun write(value: Int) {
                    entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                    bytes.write(value)
                }
                override fun write(data: ByteArray, offset: Int, count: Int) {
                    entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                    bytes.write(data, offset, count)
                }
            })
        }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            withTimeout(1000) {
                state.withSession("a") { state.output("a", "1HAFTER".toByteArray()) }
                state.withSession("b") { state.output("b", "SIBLING".toByteArray()) }
            }
        } finally { release.countDown(); writer.await(); capture.snapshot.close() }
        val snapshot = TerminalCheckpoint.read(ByteArrayInputStream(bytes.toByteArray()))
        assertTrue(snapshot.screen.transcriptText.contains("BEFORE"))
        assertFalse(snapshot.screen.transcriptText.contains("AFTER"))
        // The captured graph must preserve its unfinished CSI too.
        val restored = RuntimeTerminalStateTestOutput.emulator()
        restored.restoreState(snapshot)
        "1HRESTORED".toByteArray().let { restored.appendMirror(it, it.size) }
        assertEquals("RESTORED", restored.screen.getSelectedText(0, 3, 7, 3))
    }

    @Test fun oneSessionsCaptureCannotHoldTheSiblingLock() = runBlocking {
        val state = RuntimeTerminalState(100)
        state.create("a", 40, 8); state.create("b", 40, 8)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val holder = launch { state.withSession("a") { entered.complete(Unit); release.await() } }
        try {
            entered.await()
            withTimeout(1000) { state.withSession("b") { state.output("b", "SIBLING".toByteArray()) } }
            assertFalse(holder.isCompleted)
        } finally { release.complete(Unit); holder.join() }
    }

    @Test fun failedCaptureAndRepeatedCloseReleaseTheirBudget() {
        val budget = CheckpointBudget(32 * 1024)
        val state = RuntimeTerminalState(1000)
        state.create("large", 200, 100)
        state.output("large", ("x".repeat(180) + "\r\n").repeat(200).toByteArray())
        assertThrows(IOException::class.java) { state.subscribe("large", Capture(budget)) }
        budget.acquire(32 * 1024); budget.release(32 * 1024)
        val small = RuntimeTerminalState(10); small.create("small", 8, 4)
        val capture = Capture(budget); small.subscribe("small", capture)
        assertThrows(IOException::class.java) { budget.acquire(32 * 1024) }
        capture.snapshot.close(); capture.snapshot.close()
        budget.acquire(32 * 1024); budget.release(32 * 1024)
    }
}

private object RuntimeTerminalStateTestOutput : dev.terminal.emulator.TerminalOutput() {
    fun emulator() = TerminalEmulator(this, 8, 4, 0, 0, 100, null)
    override fun write(data: ByteArray, offset: Int, count: Int) = Unit
    override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
    override fun onCopyTextToClipboard(text: String?) = Unit
    override fun onPasteTextFromClipboard() = Unit
    override fun onBell() = Unit
    override fun onColorsChanged() = Unit
}
