package dev.terminal.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.graphics.toArgb
import dev.terminal.emulator.TerminalEmulator
import dev.terminal.emulator.TextStyle
import dev.terminal.emulator.TerminalSession
import dev.terminal.view.TerminalView
import dev.terminal.view.TerminalRenderer
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.lifecycle.VmRuntimeService
import dev.terminal.runtime.workspace.WorkspaceRegistry
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

    @Test fun nativeClipboardUsesExplicitGrantAndFocusedForegroundTerminal() = withSession { service, id ->
        val grants = dev.terminal.bridge.SharedPreferencesCapabilityGrantStore(context)
        val capability = dev.terminal.bridge.Capability.CLIPBOARD_WRITE
        val previousGrant = grants.get(NATIVE_CLIPBOARD_SCOPE, capability)
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        var previousClip: android.content.ClipData? = null
        var clipboardCaptured = false
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val terminal = select(scenario, id)
                scenario.onActivity {
                    previousClip = clipboard.primaryClip
                    clipboardCaptured = true
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("fixture", "native-clipboard-initial"))
                }
                setNativeClipboardEnabled(context, false)
                service.writeSessionInput(id, "printf native-denied | terminal-usb clipboard set; printf '\\nCLIP_%s\\n' DENIED\n".toByteArray())
                await("denied Native clipboard command completed") { screen(terminal).contains("CLIP_DENIED") }
                scenario.onActivity { assertEquals("native-clipboard-initial", clipboard.primaryClip?.getItemAt(0)?.text?.toString()) }
                setNativeClipboardEnabled(context, true)
                service.writeSessionInput(id, "printf '한글 native-clipboard-allowed' | terminal-usb clipboard set\n".toByteArray())
                await("foreground Native OSC 52 reaches the granted Android clipboard") {
                    var copied = false
                    scenario.onActivity { copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "한글 native-clipboard-allowed" }
                    copied
                }
                assertFalse(copyNativeClipboard(context, id, "background", false))
                assertFalse(copyNativeClipboard(context, "avf-fixture", "guest", true))
                assertFalse(copyNativeClipboard(context, id, "x".repeat(32769), true))
            }
        } finally {
            grants.set(NATIVE_CLIPBOARD_SCOPE, capability, previousGrant)
            instrumentation.runOnMainSync {
                if (clipboardCaptured) previousClip?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip()
            }
        }
    }

    @Test fun bundledVimAcceptsImeUtf8AndHardwareEscapeAndSavesAfterRecreation() = withSession { service, id ->
        val output = java.io.File(context.cacheDir, "vim-ui-${java.util.UUID.randomUUID()}.json")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val terminal = select(scenario, id)
                service.writeSessionInput(id, "vim '${output.absolutePath}'\n".toByteArray())
                await("Vim alternate screen: ${screen(terminal).takeLast(1000)}") {
                    var alternate = false
                    instrumentation.runOnMainSync { alternate = terminal.emulator.isAlternateBufferActive }
                    alternate
                }
                scenario.onActivity {
                    val target = checkNotNull(it.focusedTerminalView())
                    assertTrue(target.onCreateInputConnection(android.view.inputmethod.EditorInfo()).commitText("i{\"한글\":42}", 1))
                    assertTrue(target.onKeyDown(KeyEvent.KEYCODE_ESCAPE, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE)))
                    target.onKeyUp(KeyEvent.KEYCODE_ESCAPE, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))
                }
                scenario.recreate()
                select(scenario, id)
                scenario.onActivity {
                    val target = checkNotNull(it.focusedTerminalView())
                    // A multiline IME commit is intentionally a bracketed paste.
                    // Enter is a key event here, as it is when typing a Vim command.
                    assertTrue(target.onCreateInputConnection(android.view.inputmethod.EditorInfo()).commitText(":wq", 1))
                    assertTrue(target.onKeyDown(KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
                    target.onKeyUp(KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                }
                try {
                    await("Vim saved UTF-8 through IME after Activity recreation") {
                        output.isFile && output.readText() == "{\"한글\":42}\n"
                    }
                } catch (failure: AssertionError) {
                    throw AssertionError("Vim fixture file=${if (output.isFile) output.readText() else "missing"}; " +
                        "screen=${screen(terminal).takeLast(1000)}", failure)
                }
            }
        } finally { output.delete() }
    }

    @Test fun idlePromptReleasesWakeLockButSilentCommandKeepsItAfterUiDetach() = withSession { service, id ->
        val oldPolicy = service.wakePolicy
        fun held(): Boolean = instrumentation.uiAutomation.executeShellCommand("dumpsys power").use { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { reader ->
                reader.readText().lineSequence().any { it.contains("PARTIAL_WAKE_LOCK") && it.contains("DroidTerminal:guest-work") }
            }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val terminal = select(scenario, id)
                service.writeSessionInput(id, "printf 'WAKE_PROMPT_READY\\n'\n".toByteArray())
                await("prompt ready") { "WAKE_PROMPT_READY" in screen(terminal).lines() }
                service.setWakePolicy("KEEP_ACTIVE_WORK_RUNNING")
                await("idle shell releases partial wake lock") { !held() }
                service.writeSessionInput(id, "sleep 12; printf 'WAKE_COMMAND_DONE\\n'\n".toByteArray())
                await("command acquires partial wake lock") { held() }
                scenario.moveToState(Lifecycle.State.CREATED)
                val store = (context.applicationContext as TerminalApplication).terminalSessions
                await("UI mirror detached while silent command runs") {
                    var detached = false
                    instrumentation.runOnMainSync { detached = !store.hasMirror(id) }
                    detached
                }
                assertTrue("UI detach cannot release command wake lock", held())
                await("command completion releases partial wake lock") { !held() }
                scenario.moveToState(Lifecycle.State.RESUMED)
                val restored = select(scenario, id)
                await("command output survived background") { screen(restored).contains("WAKE_COMMAND_DONE") }
            }
        } finally { service.setWakePolicy(oldPolicy) }
    }

    @Test fun gracePeriodKeepsMirrorThenBackgroundDetachRestoresCheckpointAndSibling() = withSession { service, id ->
        withSession { _, sibling ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val original = select(scenario, id)
                val store = (context.applicationContext as TerminalApplication).terminalSessions
                val script = "stty -echo -icanon min 1 time 0; " +
                    "printf '\\033[2J\\033[HSHELL_BEFORE\\033[?1049h\\033[HGRACE_READY'; " +
                    "dd bs=1 count=1 >/dev/null 2>&1; " +
                    "printf '\\033[2;1HBACKGROUND_ONLY\\033[4;'; " +
                    "dd bs=1 count=1 >/dev/null 2>&1; " +
                    "printf '1HRESTORED_PARSER'; " +
                    "dd bs=1 count=1 >/dev/null 2>&1; " +
                    "printf '\\033[?1049l'; stty echo icanon\n"
                service.writeSessionInput(id, script.toByteArray())
                await("TUI ready") { screen(original).contains("GRACE_READY") }
                scenario.moveToState(Lifecycle.State.CREATED)
                instrumentation.runOnMainSync { assertTrue(store.hasMirror(id)) }
                scenario.moveToState(Lifecycle.State.RESUMED)
                assertSame(original, select(scenario, id))
                scenario.moveToState(Lifecycle.State.CREATED)
                await("background subscription detached after grace") {
                    var detached = false
                    instrumentation.runOnMainSync { detached = !store.hasMirror(id) && id !in store.sessions }
                    detached
                }
                service.writeSessionInput(id, "A".toByteArray())
                service.writeSessionInput(sibling, "printf 'SIBLING_STILL_ALIVE\\n'\n".toByteArray())
                // A fresh checkpoint proves runtime consumed output after all UI mirrors detached.
                SystemClock.sleep(300)
                assertFalse(screen(original).contains("BACKGROUND_ONLY"))
                val listed = JSONArray(service.sessionsJson)
                assertTrue((0 until listed.length()).any {
                    listed.getJSONObject(it).getString("id") == id && listed.getJSONObject(it).getString("state") == "RUNNING"
                })
                scenario.moveToState(Lifecycle.State.RESUMED)
                val restored = select(scenario, id)
                assertNotSame(original, restored)
                await("background alternate screen restored") { screen(restored).contains("BACKGROUND_ONLY") }
                instrumentation.runOnMainSync { assertTrue(restored.emulator.isAlternateBufferActive) }
                service.writeSessionInput(id, "B".toByteArray())
                await("unfinished CSI restored") { screen(restored).contains("RESTORED_PARSER") }
                service.writeSessionInput(id, "C".toByteArray())
                await("main buffer restored") { screen(restored).contains("SHELL_BEFORE") }
                val other = select(scenario, sibling)
                await("sibling survived UI detach") { screen(other).contains("SIBLING_STILL_ALIVE") }
            }
        }
    }

    @Test fun scrollbackViewportAndGraphicsRestoreAfterGraceExpiry() = withSession { service, id ->
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val original = select(scenario, id)
            val script = "i=1; while [ \$i -le 200 ]; do printf 'anchor-%s\\r\\n' \"\$i\"; i=\$((i+1)); done; " +
                "stty -echo -icanon min 1 time 0; dd bs=1 count=1 >/dev/null 2>&1; " +
                "printf '\\r\\nAFTER_DETACH\\r\\n\\033_Ga=T,i=97,f=24,s=1,v=1,C=1,q=2;/wAA\\033\\\\'; " +
                "dd bs=1 count=1 >/dev/null 2>&1; stty echo icanon\n"
            val encoded = java.util.Base64.getEncoder().encodeToString(script.toByteArray())
            service.writeSessionInput(id, "sh -c \"\$(printf %s $encoded | base64 -d)\"\n".toByteArray())
            await("runtime scrollback ready") { "anchor-200" in screen(original).lines() }
            var anchor = ""
            instrumentation.runOnMainSync {
                val terminalView = checkNotNull(view(scenario))
                terminalView.topRow = -40
                terminalView.saveSessionScrollPosition()
                anchor = original.emulator.screen.getSelectedText(0, terminalView.topRow, 30, terminalView.topRow)
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            val store = (context.applicationContext as TerminalApplication).terminalSessions
            await("scrollback mirror detached") {
                var detached = false
                instrumentation.runOnMainSync { detached = !store.hasMirror(id) }
                detached
            }
            service.writeSessionInput(id, "A".toByteArray())
            SystemClock.sleep(300)
            scenario.moveToState(Lifecycle.State.RESUMED)
            val restored = select(scenario, id)
            await("graphics and output checkpoint") {
                var done = false
                instrumentation.runOnMainSync {
                    done = restored.emulator.screen.transcriptText.contains("AFTER_DETACH") &&
                        restored.emulator.graphics.placements.any { it.imageId == 97L }
                }
                done
            }
            scenario.onActivity {
                val terminalView = checkNotNull(view(scenario))
                assertTrue("viewport must remain in history", terminalView.topRow < 0)
                assertEquals(anchor, restored.emulator.screen.getSelectedText(0, terminalView.topRow, 30, terminalView.topRow))
                assertEquals(0xffff0000.toInt(), restored.emulator.graphics.placements.first { it.imageId == 97L }.image.pixels[0])
            }
            service.writeSessionInput(id, "B".toByteArray())
        }
    }

    @Test fun toolbarModesKeepTerminalGridAdjacentToHandle() = withSession { _, id ->
        val padding = TerminalAppearance.paddingDp(context.getSharedPreferences(
            SettingsDetailActivity.TERMINAL_PREFERENCES, Context.MODE_PRIVATE)) * context.resources.displayMetrics.density
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            select(scenario, id)
            scenario.onActivity { activity ->
                activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    .hideSoftInputFromWindow(view(scenario)?.windowToken, 0)
            }
            for (mode in listOf(ToolbarSheetMode.Default, ToolbarSheetMode.Expanded, ToolbarSheetMode.Hidden, ToolbarSheetMode.Default)) {
                scenario.onActivity { activity ->
                    @Suppress("UNCHECKED_CAST")
                    val state = MainActivity::class.java.getDeclaredField("toolbarSheetMode\$delegate")
                        .apply { isAccessible = true }.get(activity) as androidx.compose.runtime.MutableState<ToolbarSheetMode>
                    state.value = mode
                }
                // Let the requested mode render before checking its geometry.
                SystemClock.sleep(1500)
                val label = context.getString(R.string.toolbar_handle)
                fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                    if (node == null) return null
                    if (node.isVisibleToUser && node.contentDescription?.toString() == label) return node
                    for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
                    return null
                }
                var lastWindow = "$mode: no accessibility window observed"
                var lastGeometry = "terminal geometry not yet available"
                try {
                    await("$mode: terminal grid reaches the toolbar handle") {
                        instrumentation.uiAutomation.clearCache()
                        val root = instrumentation.uiAutomation.rootInActiveWindow
                        val handle = find(root)
                        if (handle == null) {
                            lastWindow = "$mode: missing $label, package=${root?.packageName}"
                            false
                        } else {
                            val bounds = Rect().also { handle.getBoundsInScreen(it) }
                            lastWindow = "$mode: handle=$bounds"
                            var adjacent = false
                            scenario.onActivity { activity ->
                                val terminalView = activity.focusedTerminalView()
                                val emulator = terminalView?.currentSession?.emulator
                                if (terminalView != null && emulator != null) {
                                    val renderer = TerminalView::class.java.getDeclaredField("mRenderer")
                                        .apply { isAccessible = true }.get(terminalView) as TerminalRenderer
                                    val inset = terminalView.terminalContentTopInset
                                    val top = TerminalRenderer::class.java.getDeclaredField("mFontLineSpacingAndAscent")
                                        .apply { isAccessible = true }.getInt(renderer)
                                    val location = IntArray(2).also { terminalView.getLocationOnScreen(it) }
                                    val gridBottom = location[1] + inset + top + emulator.mRows * renderer.fontLineSpacing
                                    val gap = bounds.top - gridBottom
                                    lastGeometry = "gap=$gap, padding=$padding, view=${terminalView.height}, rows=${emulator.mRows}, " +
                                        "line=${renderer.fontLineSpacing}, top=${location[1]}, inset=$inset"
                                    adjacent = kotlin.math.abs(gap - padding) <= 1
                                }
                            }
                            adjacent
                        }
                    }
                } catch (failure: AssertionError) {
                    throw AssertionError("$lastWindow; last geometry: $lastGeometry", failure)
                }
            }
        }
    }

    @Test fun tuiChromeFollowsLiveOutputAndRestoresAcrossSettingsAndRecreation() = withSession { service, id ->
        val preferences = context.getSharedPreferences(SettingsDetailActivity.TERMINAL_PREFERENCES, Context.MODE_PRIVATE)
        val key = TerminalAppearance.KEY_FOLLOW_TUI_BACKGROUND
        val original = preferences.all[key] as? Boolean
        try {
            preferences.edit().putBoolean(key, true).commit()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val terminal = select(scenario, id)
                val background = terminal.emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]
                fun chrome(): Int {
                    var result = 0
                    scenario.onActivity {
                        val state = MainActivity::class.java.getDeclaredField("terminalChromeColors\$delegate")
                            .apply { isAccessible = true }.get(it) as androidx.compose.runtime.State<*>
                        result = (state.value as dev.terminal.app.ui.TerminalChromeColors).background.toArgb()
                    }
                    return result
                }
                fun assertCanvas(expected: Int) {
                    scenario.onActivity {
                        val terminalView = checkNotNull(view(scenario))
                        val bitmap = Bitmap.createBitmap(terminalView.width, terminalView.height, Bitmap.Config.ARGB_8888)
                        terminalView.draw(Canvas(bitmap))
                        assertEquals("Terminal canvas top margin", expected, bitmap.getPixel(0, 0))
                        bitmap.recycle()
                    }
                }
                val script = "stty -echo -icanon min 1 time 0; " +
                    "printf '\\033[?1049h\\033[48;2;18;52;86m\\033[2J\\033[HTUI_DARK'; " +
                    "dd bs=1 count=1 >/dev/null 2>&1; " +
                    "printf '\\033[?2026h\\033[48;2;240;224;208m\\033[2J\\033[HTUI_LIGHT'; " +
                    "dd bs=1 count=1 >/dev/null 2>&1; printf '\\033[?2026l'; " +
                    "dd bs=1 count=1 >/dev/null 2>&1; " +
                    "printf '\\033[0m\\033[?1049l'; stty echo icanon\n"
                service.writeSessionInput(id, script.toByteArray())
                await("dark TUI chrome") { chrome() == 0xFF123456.toInt() }
                assertCanvas(0xFF123456.toInt())
                assertEquals(background, terminal.emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND])
                service.writeSessionInput(id, "A".toByteArray())
                await("synchronized theme received") { screen(terminal).contains("TUI_LIGHT") }
                assertEquals(0xFF123456.toInt(), chrome())
                service.writeSessionInput(id, "S".toByteArray())
                await("live light theme") { chrome() == 0xFFF0E0D0.toInt() }
                assertCanvas(0xFFF0E0D0.toInt())
                scenario.recreate()
                select(scenario, id)
                await("TUI chrome after recreation") { chrome() == 0xFFF0E0D0.toInt() }
                assertCanvas(0xFFF0E0D0.toInt())
                scenario.moveToState(Lifecycle.State.CREATED)
                preferences.edit().putBoolean(key, false).commit()
                scenario.moveToState(Lifecycle.State.RESUMED)
                await("disabled option restores palette") { chrome() == background }
                assertCanvas(background)
                scenario.moveToState(Lifecycle.State.CREATED)
                preferences.edit().putBoolean(key, true).commit()
                scenario.moveToState(Lifecycle.State.RESUMED)
                await("enabled option restores TUI color") { chrome() == 0xFFF0E0D0.toInt() }
                service.writeSessionInput(id, "B".toByteArray())
                await("TUI exit restores palette") { chrome() == background }
            }
        } finally {
            preferences.edit().apply { if (original == null) remove(key) else putBoolean(key, original) }.commit()
        }
    }

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
            service.writeSessionInput(id, "i=1; while [ \$i -le 200 ]; do printf 'history-%s\\n' \"\$i\"; i=\$((i+1)); done\n".toByteArray())
            await("runtime history available") { "history-200" in screen(terminal).lines() }
            instrumentation.runOnMainSync {
                view(scenario)!!.onScreenUpdated()
                view(scenario)!!.topRow = -40
                anchoredText = terminal.emulator.screen.getSelectedText(0, -40, 30, -40)
                view(scenario)!!.saveSessionScrollPosition()
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
            result = it.focusedTerminalView()
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
        var previousWorkspace: String? = null
        val service = requireNotNull(queue.poll(15, TimeUnit.SECONDS))
        try {
            previousWorkspace = WorkspaceRegistry.State.parse(service.workspacesJson).selected
            assertEquals("Select the fixture's unassigned Workspace", "", service.selectWorkspace(WorkspaceRegistry.UNASSIGNED))
            service.startRuntime()
            await("Native runtime ready") { service.state == "READY" }
            fun ids() = JSONArray(service.sessionsJson).let { array -> (0 until array.length()).map { array.getJSONObject(it).getString("id") }.toSet() }
            val previous = ids()
            service.createSession("NATIVE", "/system/bin/sh", "", 24, 80)
            await("test PTY") { owned = (ids() - previous).singleOrNull(); owned != null }
            test(service, checkNotNull(owned))
        } finally {
            try {
                owned?.let(service::terminateSession)
            } finally {
                try {
                    previousWorkspace?.let { assertEquals("Restore the original Workspace", "", service.selectWorkspace(it)) }
                } finally {
                    context.unbindService(connection)
                }
            }
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
