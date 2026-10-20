package dev.terminal.app.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.runtime.CompositionLocalProvider

data class TerminalHapticSettings(
    val enabled: Boolean,
    val scrollEnabled: Boolean,
) {
    fun scrollRowsPerTick(): Int = if (enabled && scrollEnabled) 1 else 0
}

object TerminalHapticPreferences {
    const val PREFERENCES_NAME = "terminal-ui"
    const val KEY_ENABLED = "haptic-feedback-enabled"
    const val KEY_SCROLL = "haptic-feedback-scroll"

    fun read(preferences: SharedPreferences): TerminalHapticSettings = TerminalHapticSettings(
        enabled = preferences.getBoolean(KEY_ENABLED, true),
        scrollEnabled = preferences.getBoolean(KEY_SCROLL, false),
    )
}

class TerminalHaptics internal constructor(
    private val platform: HapticFeedback,
    private val settings: TerminalHapticSettings,
) {
    fun performHapticFeedback(type: HapticFeedbackType) {
        if (!settings.enabled) return
        platform.performHapticFeedback(type)
    }

    /** Continuous value controls use the same master switch as other feedback. */
    fun performDenseHapticFeedback(type: HapticFeedbackType) {
        performHapticFeedback(type)
    }
}

val LocalTerminalHaptics = staticCompositionLocalOf<TerminalHaptics> {
    error("Terminal haptic feedback provider is missing")
}

@Composable
fun TerminalHapticFeedbackProvider(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences(TerminalHapticPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE)
    }
    var settings by remember { mutableStateOf(TerminalHapticPreferences.read(preferences)) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in setOf(TerminalHapticPreferences.KEY_ENABLED, TerminalHapticPreferences.KEY_SCROLL)) {
                settings = TerminalHapticPreferences.read(preferences)
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val haptics = LocalHapticFeedback.current
    val controlled = remember(haptics, settings) { TerminalHaptics(haptics, settings) }
    CompositionLocalProvider(LocalTerminalHaptics provides controlled, content = content)
}
