package dev.terminal.app

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.termux.terminal.TerminalBuffer
import com.termux.terminal.TerminalCheckpoint
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.lifecycle.VmRuntimeService
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONArray

/** Repeatable process-death regression driven by adb; never runs in a release build. */
class TerminalProcessDeathProbe : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            val directory = File(context.cacheDir, "terminal-process-death").apply { mkdirs() }
            try {
                val result = withRuntime(context.applicationContext) { runtime ->
                    when (intent.getStringExtra("operation")) {
                        "prepare" -> prepare(context, runtime, directory)
                        "verify" -> verify(context, runtime, directory)
                        "prepare-viewport" -> prepareViewport(context, runtime, directory)
                        "verify-viewport" -> verifyViewport(context, runtime, directory)
                        "cleanup" -> {
                            File(directory, "id").takeIf { it.exists() }?.readText()?.let(runtime::terminateSession)
                            directory.listFiles()?.forEach(File::delete)
                            File(context.filesDir, "native/tmp/terminal-checkpoint-probe.rgb").delete()
                            "CLEANED"
                        }
                        else -> error("Unknown probe operation")
                    }
                }
                File(directory, "result").writeText(result)
            } catch (failure: Throwable) {
                File(directory, "result").writeText("FAIL: ${failure.stackTraceToString()}")
            } finally { pending.finish() }
        }.start()
    }

    private fun newSession(context: Context, runtime: IRuntimeService, directory: File): Pair<String, TerminalSessionStore> {
        check(!File(directory, "id").exists()) { "Clean up the previous probe first" }
        await { runtime.state == "READY" }
        val before = ids(runtime)
        runtime.createSession("NATIVE", "/system/bin/sh", "", 24, 80)
        var id = ""
        await { id = (ids(runtime) - before).singleOrNull().orEmpty(); id.isNotEmpty() }
        File(directory, "id").writeText(id)
        val store = (context.applicationContext as TerminalApplication).terminalSessions
        await {
            onMain {
                val owner = TerminalSessionStore::class.java.getDeclaredField("observerOwner").apply { isAccessible = true }.get(store)
                if (owner !is MainActivity) false else {
                    MainActivity::class.java.getDeclaredMethod("attach", String::class.java).apply { isAccessible = true }.invoke(owner, id)
                    store.sessions[id]?.emulator != null
                }
            }
        }
        return id to store
    }

    private fun prepare(context: Context, runtime: IRuntimeService, directory: File): String {
        val (id, store) = newSession(context, runtime, directory)
        val script = "stty -echo -icanon min 1 time 0; " +
            "i=0; while [ \$i -lt 150 ]; do printf 'history-%s\\r\\n' \"\$i\"; i=\$((i+1)); done; " +
            "printf '\\033[?1049h\\033[?45h\\033[20h\\033[?1003h\\033[?1006h\\033[?1007l\\033[?2004h\\033[2J\\033[H\\033[38;2;12;34;56mTUI_READY\\033[2;2H'; " +
            "dd if=/dev/urandom of=\"\$TMPDIR/terminal-checkpoint-probe.rgb\" bs=393216 count=3 2>/dev/null; " +
            "printf '\\033_Ga=T,i=42,p=7,t=f,f=24,s=768,v=512,c=2,r=1,C=1,q=2;'; " +
            "printf '%s' \"\$TMPDIR/terminal-checkpoint-probe.rgb\" | base64 -w 0; printf '\\033\\\\\\033[4;'; " +
            "dd bs=1 count=1 of=/dev/null 2>/dev/null; sleep 8; dd if=/dev/zero bs=1024 count=9216 2>/dev/null; " +
            "printf '1HDURING_UI_DEATH\\033[H\\033[6n'; reply=\$(dd bs=1 count=6 2>/dev/null); " +
            "case \"\$reply\" in *'[1;1R') printf '\\033[5;1HQUERY_WITHOUT_UI_OK';; *) printf '\\033[5;1HQUERY_FAILED';; esac; " +
            "printf '\\033]2;RESTORED_TITLE'; dd bs=1 count=1 of=/dev/null 2>/dev/null; " +
            "printf '\\007\\033[6;1HPARSER_CONTINUED'; sleep 120\n"
        writeProbeScript(runtime, id, script)
        try {
            await(45_000) { onMain { store.sessions[id]?.emulator?.let { screen(it).contains("TUI_READY") && parserState(it) == 6 } == true } }
        } catch (failure: Exception) {
            val detail = onMain { store.sessions[id]?.emulator?.let { "parser=${parserState(it)} images=${it.graphics.placements.size} cursor=${it.cursorRow}/${it.cursorCol} screen=${screen(it).take(200)}" } }
            throw IllegalStateException(detail, failure)
        }
        onMain {
            File(directory, "expected").outputStream().use { TerminalCheckpoint.write(store.sessions.getValue(id).emulator, it) }
        }
        runtime.writeSessionInput(id, byteArrayOf('X'.code.toByte()))
        return "READY uiPid=${android.os.Process.myPid()}"
    }

    private fun verify(context: Context, runtime: IRuntimeService, directory: File): String {
        val id = File(directory, "id").readText()
        val store = (context.applicationContext as TerminalApplication).terminalSessions
        val expected = TerminalEmulator(object : TerminalOutput() {
            override fun write(bytes: ByteArray, offset: Int, count: Int) = Unit
            override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
            override fun onCopyTextToClipboard(text: String?) = Unit
            override fun onPasteTextFromClipboard() = Unit
            override fun onBell() = Unit
            override fun onColorsChanged() = Unit
        }, 2, 2, 1, 1, 100, null)
        expected.restoreState(File(directory, "expected").inputStream().use(TerminalCheckpoint::read))
        val suffix = "1HDURING_UI_DEATH\u001b[H\u001b[6n\u001b[5;1HQUERY_WITHOUT_UI_OK\u001b]2;RESTORED_TITLE".toByteArray()
        expected.appendMirror(suffix, suffix.size)
        await { onMain {
            store.sessions[id]?.emulator?.let { screen(it).contains("QUERY_WITHOUT_UI_OK") && parserState(it) == 10 && it.mRows == expected.mRows && it.mColumns == expected.mColumns } == true
        } }
        onMain {
            val actual = store.sessions.getValue(id).emulator
            compareScreens(expected.screen, actual.screen, expected.mColumns, expected.mRows)
            compareScreens(mainBuffer(expected), mainBuffer(actual), expected.mColumns, expected.mRows)
            check(actual.isAlternateBufferActive && actual.isMouseMotionTrackingActive && !actual.isAlternateScrollEnabled)
            check(actual.isNewlineModeEnabled && actual.getKeyCode(66, 0) == "\r\n")
            check(expected.mColors.mCurrentColors.contentEquals(actual.mColors.mCurrentColors))
            check(actual.cursorRow == expected.cursorRow && actual.cursorCol == expected.cursorCol)
            val image = actual.graphics.placements.single()
            check(image.imageId == 42L && image.placementId == 7L)
            check(image.image.width == 768 && image.image.height == 512)
            check(expected.graphics.placements.single().image.pixels.contentEquals(image.image.pixels))
            check(File(directory, "expected").length() > 1024 * 1024) { "Checkpoint must exceed the Binder byte-array limit" }
        }
        runtime.writeSessionInput(id, byteArrayOf('Y'.code.toByte()))
        await { onMain { store.sessions[id]?.emulator?.let { screen(it).contains("PARSER_CONTINUED") && it.title == "RESTORED_TITLE" } == true } }
        return "PASS uiPid=${android.os.Process.myPid()} both buffers/history/styles/colors/image/mouse/partial CSI+OSC; query answered while UI absent; 9MiB output; checkpoint >1MiB"
    }

    private fun prepareViewport(context: Context, runtime: IRuntimeService, directory: File): String {
        val (id, store) = newSession(context, runtime, directory)
        val script = "stty -echo -icanon min 1 time 0; i=0; " +
            "while [ \$i -lt 200 ]; do printf 'anchor-%s\\r\\n' \"\$i\"; i=\$((i+1)); done; " +
            "printf 'VIEWPORT_READY\\r\\n'; dd bs=1 count=1 of=/dev/null 2>/dev/null; sleep 8; " +
            "i=0; while [ \$i -lt 20 ]; do printf 'AFTER_UI_DEATH-%s\\r\\n' \"\$i\"; i=\$((i+1)); done; " +
            "dd bs=1 count=1 of=/dev/null 2>/dev/null; sleep 120\n"
        writeProbeScript(runtime, id, script)
        await { onMain { store.sessions[id]?.emulator?.let { screen(it).endsWith("VIEWPORT_READY") } == true } }
        onMain {
            val view = currentView(store)
            view.topRow = -40
            view.saveSessionScrollPosition()
            File(directory, "anchor").writeText(view.currentSession.emulator.screen.getSelectedText(0, view.topRow, 30, view.topRow))
        }
        runtime.writeSessionInput(id, byteArrayOf('X'.code.toByte()))
        return "READY viewport uiPid=${android.os.Process.myPid()}"
    }

    private fun verifyViewport(context: Context, runtime: IRuntimeService, directory: File): String {
        val id = File(directory, "id").readText()
        val anchor = File(directory, "anchor").readText()
        val store = (context.applicationContext as TerminalApplication).terminalSessions
        await { onMain { store.sessions[id]?.emulator?.let { screen(it).contains("AFTER_UI_DEATH-19") } == true } }
        onMain {
            val view = currentView(store)
            check(view.currentSession === store.sessions[id])
            check(view.topRow < 0) { "Viewport returned to the prompt" }
            check(view.currentSession.emulator.screen.getSelectedText(0, view.topRow, 30, view.topRow) == anchor) { "Saved viewport anchor was lost" }
        }
        runtime.writeSessionInput(id, byteArrayOf('Y'.code.toByte()))
        return "PASS viewport uiPid=${android.os.Process.myPid()} anchor preserved across UI death and background output"
    }

    private fun currentView(store: TerminalSessionStore): com.termux.view.TerminalView {
        val owner = TerminalSessionStore::class.java.getDeclaredField("observerOwner").apply { isAccessible = true }.get(store) as MainActivity
        return MainActivity::class.java.getDeclaredField("terminalView").apply { isAccessible = true }.get(owner) as com.termux.view.TerminalView
    }

    private fun writeProbeScript(runtime: IRuntimeService, id: String, script: String) {
        // Decode the fixture inside sh -c so mksh's interactive line editor cannot
        // consume backslashes in separate printf/pipeline commands.
        val encoded = java.util.Base64.getEncoder().encodeToString(script.toByteArray())
        runtime.writeSessionInput(id, "sh -c \"\$(printf %s $encoded | base64 -d)\"\n".toByteArray())
    }

    private fun compareScreens(expected: TerminalBuffer, actual: TerminalBuffer, columns: Int, rows: Int) {
        check(expected.activeTranscriptRows == actual.activeTranscriptRows) { "History row count" }
        for (row in -expected.activeTranscriptRows until rows) {
            check(expected.getSelectedText(0, row, columns - 1, row, false, false) == actual.getSelectedText(0, row, columns - 1, row, false, false)) { "Text row $row" }
            check(expected.getLineWrap(row) == actual.getLineWrap(row)) { "Wrap row $row" }
            for (column in 0 until columns) check(expected.getStyleAt(row, column) == actual.getStyleAt(row, column)) { "Style $row/$column" }
        }
    }
    private fun mainBuffer(emulator: TerminalEmulator) = TerminalEmulator::class.java.getDeclaredField("mMainBuffer").apply { isAccessible = true }.get(emulator) as TerminalBuffer
    private fun parserState(emulator: TerminalEmulator) = TerminalEmulator::class.java.getDeclaredField("mEscapeState").apply { isAccessible = true }.getInt(emulator)
    private fun screen(emulator: TerminalEmulator) = emulator.screen.transcriptText
    private fun ids(runtime: IRuntimeService): Set<String> = JSONArray(runtime.sessionsJson).let { values -> (0 until values.length()).mapTo(mutableSetOf()) { values.getJSONObject(it).getString("id") } }
    private fun await(timeout: Long = 20_000, check: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + timeout
        while (!check()) { check(android.os.SystemClock.uptimeMillis() < deadline) { "Probe timed out" }; Thread.sleep(25) }
    }
    private fun <T> onMain(action: () -> T): T {
        val done = CountDownLatch(1); var result: Result<T>? = null
        Handler(Looper.getMainLooper()).post { result = runCatching(action); done.countDown() }
        check(done.await(10, TimeUnit.SECONDS)) { "Main thread stalled" }
        return result!!.getOrThrow()
    }
    private fun <T> withRuntime(context: Context, action: (IRuntimeService) -> T): T {
        val queue = LinkedBlockingQueue<IRuntimeService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { queue.offer(IRuntimeService.Stub.asInterface(binder)) }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        check(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
        try { return action(checkNotNull(queue.poll(10, TimeUnit.SECONDS))) }
        finally { context.unbindService(connection) }
    }
}
