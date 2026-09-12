package dev.terminal.app.ui

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalHapticsTest {
    private class RecordingHaptics : HapticFeedback {
        val events = mutableListOf<HapticFeedbackType>()
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            events += hapticFeedbackType
        }
    }

    @Test fun scrollTicksRespectMasterSwitchAndDensity() {
        assertEquals(0, TerminalHapticSettings(false, TerminalHapticDensity.HIGH, true).scrollRowsPerTick())
        assertEquals(0, TerminalHapticSettings(true, TerminalHapticDensity.HIGH, false).scrollRowsPerTick())
        assertEquals(6, TerminalHapticSettings(true, TerminalHapticDensity.LOW, true).scrollRowsPerTick())
        assertEquals(3, TerminalHapticSettings(true, TerminalHapticDensity.STANDARD, true).scrollRowsPerTick())
        assertEquals(1, TerminalHapticSettings(true, TerminalHapticDensity.HIGH, true).scrollRowsPerTick())
    }

    @Test fun masterSwitchAndDensityGateComposeHaptics() {
        val recording = RecordingHaptics()
        TerminalHaptics(recording, TerminalHapticSettings(false, TerminalHapticDensity.HIGH, true))
            .performHapticFeedback(HapticFeedbackType.Confirm)
        TerminalHaptics(recording, TerminalHapticSettings(true, TerminalHapticDensity.LOW, true))
            .performHapticFeedback(HapticFeedbackType.KeyboardTap)
        TerminalHaptics(recording, TerminalHapticSettings(true, TerminalHapticDensity.STANDARD, true))
            .performHapticFeedback(HapticFeedbackType.KeyboardTap)
        assertEquals(listOf(HapticFeedbackType.KeyboardTap), recording.events)
    }
}
