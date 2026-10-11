package dev.terminal.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.emulator.TerminalSession
import dev.terminal.app.ui.SessionUi
import dev.terminal.app.ui.resolvedSessionTitle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalTitleDeliveryTest {
    @Test fun liveOscAndTitleRestoreReachTabResolutionWithoutOverridingExplicitTitle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val backend = RemoteTerminalBackend(write = {}, resize = { _, _ -> })
        var applicationTitle: String? = null
        val session = SessionUi("test", "NATIVE", "Profile", "RUNNING")
        instrumentation.runOnMainSync {
            TerminalSession("", "/", emptyArray(), emptyArray(), 100,
                TerminalCallbacks(titleChanged = { applicationTitle = it.title }, redraw = {}), backend)
                .initializeEmulator(80, 24, 10, 20)
        }
        fun output(bytes: String, expected: String) {
            backend.output(bytes.toByteArray())
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(expected, resolvedSessionTitle(session, applicationTitle))
                assertEquals("Pinned", resolvedSessionTitle(session.copy(title = "Pinned", hasExplicitTabTitle = true), applicationTitle))
            }
        }
        try {
            output("\u001b[22;2t\u001b]2;file.txt - VIM\u0007", "file.txt - VIM")
            output("\u001b]2;second.txt - VIM\u001b\\", "second.txt - VIM")
            output("\u001b[23;2t", "Profile")
        } finally {
            backend.close()
        }
    }
}
