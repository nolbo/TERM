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

    @Test fun scrollTicksRespectBothSwitchesAndUseEveryRowWhenEnabled() {
        assertEquals(0, TerminalHapticSettings(false, true).scrollRowsPerTick())
        assertEquals(0, TerminalHapticSettings(false, false).scrollRowsPerTick())
        assertEquals(0, TerminalHapticSettings(true, false).scrollRowsPerTick())
        assertEquals(1, TerminalHapticSettings(true, true).scrollRowsPerTick())
    }

    @Test fun enablingHapticsIncludesKeyboardTapsAndContinuousAdjustments() {
        val recording = RecordingHaptics()
        val haptics = TerminalHaptics(recording, TerminalHapticSettings(true, false))
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
        haptics.performDenseHapticFeedback(HapticFeedbackType.KeyboardTap)
        assertEquals(listOf(HapticFeedbackType.Confirm, HapticFeedbackType.KeyboardTap, HapticFeedbackType.KeyboardTap), recording.events)
    }

    @Test fun masterSwitchDisablesBothNormalAndContinuousFeedback() {
        val recording = RecordingHaptics()
        val haptics = TerminalHaptics(recording, TerminalHapticSettings(false, true))
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
        haptics.performDenseHapticFeedback(HapticFeedbackType.KeyboardTap)
        assertEquals(emptyList<HapticFeedbackType>(), recording.events)
    }
}
