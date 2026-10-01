package dev.terminal.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TextStyle
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.lifecycle.VmRuntimeService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalTuiLifecycleTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun fullscreenVtModesAndShellCursorSurviveBackgroundAndCheckpointReattach() = withSession { service, id ->
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val original = select(scenario, id)
            val script = "stty -echo -icanon min 1 time 0; " +
                "printf '\\033[2J\\033[H\\033[32mSHELL_BASE\\033[3;4H\\033[?1l\\033[?1s\\033[?1h\\033[?1049h\\033[31m\\033[?45;1003;1006;2004h\\033[20h\\033[HVT_READY'; " +
                "dd bs=1 count=1 >/dev/null 2>&1; " +
                "printf '\\033[2;5r\\033[?6h\\033[2;2HQ\\033[?1r\\033[6n'; " +
                "answer=\$(dd bs=1 count=6 2>/dev/null); " +
                "case \"\$answer\" in *'[2;3R') printf '\\033[4;1HVT_QUERY_OK';; *) printf '\\033[4;1HVT_QUERY_FAIL';; esac; " +
                "dd bs=1 count=1 >/dev/null 2>&1; " +
                "printf '\\033[?6l\\033[?1049lMAIN_CURSOR_RESTORED'; " +
                "dd bs=1 count=1 >/dev/null 2>&1; stty echo icanon\n"
            service.writeSessionInput(id, script.toByteArray())
            await("VT ready") { screen(original).contains("VT_READY") }
            scenario.moveToState(Lifecycle.State.CREATED)
            service.writeSessionInput(id, "A".toByteArray())
            await("origin-relative query while backgrounded") { screen(original).contains("VT_QUERY_OK") }

            // Discard only our UI mirror, keeping the actual PTY and runtime emulator alive.
            // The next Activity installs a checkpoint, including both cursor save slots.
            instrumentation.runOnMainSync { (context.applicationContext as TerminalApplication).terminalSessions.remove(id) }
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.recreate()
            val restored = select(scenario, id)
            assertNotSame(original, restored)
            await("checkpoint installed in the new mirror") { screen(restored).contains("VT_QUERY_OK") }
            instrumentation.runOnMainSync {
                assertTrue(restored.emulator.isAlternateBufferActive)
                assertTrue(restored.emulator.isMouseMotionTrackingActive)
                assertFalse(restored.emulator.isCursorKeysApplicationMode)
                assertTrue(restored.emulator.isNewlineModeEnabled)
            }
            service.writeSessionInput(id, "B".toByteArray())
            await("saved main screen and cursor") { screen(restored).contains("MAIN_CURSOR_RESTORED") }
            instrumentation.runOnMainSync {
                val emulator = restored.emulator
                assertFalse(emulator.isAlternateBufferActive)
                assertEquals("SHELL_BASE", emulator.screen.getSelectedText(0, 0, 9, 0))
                assertEquals("MAIN_CURSOR_RESTORED", emulator.screen.getSelectedText(3, 2, 22, 2))
                assertEquals(2, TextStyle.decodeForeColor(emulator.screen.getStyleAt(2, 3)))
            }
            service.writeSessionInput(id, "C".toByteArray())
        }
    }

    @Test fun nativeTuiKeepsItsScreenParserAndMouseInputAcrossBackgroundAndRecreation() = withSession { service, id ->
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val terminal = select(scenario, id)
            // A separate test PTY behaves like a full-screen TUI: it owns its scroll
            // position, emits a background update and reads an SGR wheel event.
            val script = "stty -echo -icanon min 1 time 0; " +
                "printf '\\033[?1049h\\033[?1000h\\033[?1002h\\033[?1003h\\033[?1006h\\033[?1007l\\033[?2004h\\033[2J\\033[HPROGRAM_SCROLL'; " +
                "(sleep 1; printf '\\033[2;1HBACKGROUND_OUTPUT') & " +
                "answer=\$(dd bs=1 count=10 2>/dev/null); " +
                "case \"\$answer\" in *'[<64;1;1M') printf '\\033[3;1HSCROLL_OK';; *) printf '\\033[3;1HSCROLL_FAIL';; esac; " +
                "stty echo icanon\n"
            service.writeSessionInput(id, script.toByteArray())
            await("TUI ready") { screen(terminal).contains("PROGRAM_SCROLL") && terminal.emulator.isMouseMotionTrackingActive }
            scenario.moveToState(Lifecycle.State.CREATED)
            await("output while Activity stopped") { screen(terminal).contains("BACKGROUND_OUTPUT") }
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertSame(terminal, select(scenario, id))

            // Leave a CSI unfinished exactly across the Activity boundary. Replaying
            // or resetting the emulator would discard this parser state.
            instrumentation.runOnMainSync {
                val bytes = "\u001b[4;".toByteArray(); terminal.emulator.append(bytes, bytes.size)
            }
            scenario.recreate()
            assertSame(terminal, select(scenario, id))
            instrumentation.runOnMainSync {
                val bytes = "1HPARSER_OK".toByteArray(); terminal.emulator.append(bytes, bytes.size)
                assertEquals("PARSER_OK", terminal.emulator.screen.getSelectedText(0, 3, 8, 3))
                assertTrue(terminal.emulator.isAlternateBufferActive)
                assertTrue(terminal.emulator.isMouseMotionTrackingActive)
                assertFalse(terminal.emulator.isAlternateScrollEnabled)
                terminal.emulator.sendMouseEvent(TerminalEmulator.MOUSE_WHEELUP_BUTTON, 1, 1, true)
            }
            await("wheel must reach the original program after recreation") { screen(terminal).contains("SCROLL_OK") }
            assertTrue(screen(terminal).contains("BACKGROUND_OUTPUT"))
        }
    }

    @Test fun destroyedActivityStillReceivesOutputAndRestoresItsScrollAnchor() = withSession { service, id ->
        lateinit var terminal: TerminalSession
        var anchoredText = ""
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            terminal = select(scenario, id)
            instrumentation.runOnMainSync {
                val bytes = (1..200).joinToString("\r\n") { "history-$it" }.toByteArray()
                terminal.emulator.append(bytes, bytes.size)
                view(scenario)!!.onScreenUpdated()
                view(scenario)!!.topRow = -40
                anchoredText = terminal.emulator.screen.getSelectedText(0, -40, 30, -40)
            }
        }
        // No Activity owns or observes this terminal now. Its application-scoped
        // subscription must keep both output and parser state current.
        service.writeSessionInput(id, "printf '\\nAFTER_DESTROY\\n'\n".toByteArray())
        await("output without an Activity") { screen(terminal).contains("AFTER_DESTROY") }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertSame(terminal, select(scenario, id))
            scenario.onActivity {
                val restored = view(scenario)!!
                assertTrue("restored top row ${restored.topRow}", restored.topRow < 0)
                assertEquals(anchoredText, terminal.emulator.screen.getSelectedText(0, restored.topRow, 30, restored.topRow))
            }
        }
    }

    @Test fun activityCanRegisterWhileContinuousOutputUsesTheBoundedReader() = withSession { service, id ->
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val terminal = select(scenario, id)
            // mksh's builtin avoids spawning 12,000 Android printf processes. This
            // keeps the test focused on output backpressure and Activity registration.
            service.writeSessionInput(id, ("i=0; while [ \$i -lt 12000 ]; do print -r -- \"burst-\$i abcdefghijklmnopqrstuvwxyz\"; " +
                "i=\$((i+1)); done; print; print -r -- BURST_COMPLETE\n").toByteArray())
            scenario.recreate()
            assertSame(terminal, select(scenario, id))
            await("output and Activity registration must both complete") { "BURST_COMPLETE" in screen(terminal).lines() }
        }
    }

    private fun select(scenario: ActivityScenario<MainActivity>, id: String): TerminalSession {
        var selected: TerminalSession? = null
        val method = MainActivity::class.java.getDeclaredMethod("attach", String::class.java).apply { isAccessible = true }
        await("select test session") {
            scenario.onActivity {
                method.invoke(it, id)
                val expected = (it.application as TerminalApplication).terminalSessions.sessions[id]
                selected = view(scenario)?.currentSession?.takeIf { it === expected && it.emulator != null }
            }
            selected != null
        }
        return checkNotNull(selected)
    }

    private fun view(scenario: ActivityScenario<MainActivity>): TerminalView? {
        var result: TerminalView? = null
        scenario.onActivity {
            result = MainActivity::class.java.getDeclaredField("terminalView").apply { isAccessible = true }.get(it) as? TerminalView
        }
        return result
    }

    private fun screen(terminal: TerminalSession): String {
        var result = ""
        instrumentation.runOnMainSync { result = terminal.emulator.screen.transcriptText }
        return result
    }

    private fun withSession(test: (IRuntimeService, String) -> Unit) {
        val queue = LinkedBlockingQueue<IRuntimeService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { queue.offer(IRuntimeService.Stub.asInterface(binder)) }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
        var owned: String? = null
        val service = requireNotNull(queue.poll(15, TimeUnit.SECONDS))
        try {
            service.startRuntime()
            await("Native runtime ready") { service.state == "READY" }
            fun ids() = JSONArray(service.sessionsJson).let { array -> (0 until array.length()).map { array.getJSONObject(it).getString("id") }.toSet() }
            val previous = ids()
            service.createSession("NATIVE", "/system/bin/sh", "", 24, 80)
            await("test PTY") { owned = (ids() - previous).singleOrNull(); owned != null }
            test(service, checkNotNull(owned))
        } finally {
            owned?.let(service::terminateSession)
            context.unbindService(connection)
        }
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        throw AssertionError(description)
    }
}
