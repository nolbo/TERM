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

enum class TerminalHapticDensity { LOW, STANDARD, HIGH }

data class TerminalHapticSettings(
    val enabled: Boolean,
    val density: TerminalHapticDensity,
    val scrollEnabled: Boolean,
) {
    fun scrollRowsPerTick(): Int = when {
        !enabled || !scrollEnabled -> 0
        density == TerminalHapticDensity.LOW -> 6
        density == TerminalHapticDensity.STANDARD -> 3
        else -> 1
    }
}

object TerminalHapticPreferences {
    const val PREFERENCES_NAME = "terminal-ui"
    const val KEY_ENABLED = "haptic-feedback-enabled"
    const val KEY_DENSITY = "haptic-feedback-density"
    const val KEY_SCROLL = "haptic-feedback-scroll"

    fun read(preferences: SharedPreferences): TerminalHapticSettings = TerminalHapticSettings(
        enabled = preferences.getBoolean(KEY_ENABLED, true),
        density = runCatching { TerminalHapticDensity.valueOf(preferences.getString(KEY_DENSITY, TerminalHapticDensity.STANDARD.name)!!) }
            .getOrDefault(TerminalHapticDensity.STANDARD),
        scrollEnabled = preferences.getBoolean(KEY_SCROLL, false),
    )
}

class TerminalHaptics internal constructor(
    private val platform: HapticFeedback,
    private val settings: TerminalHapticSettings,
) {
    fun performHapticFeedback(type: HapticFeedbackType) {
        if (!settings.enabled) return
        if (settings.density == TerminalHapticDensity.LOW && type == HapticFeedbackType.KeyboardTap) return
        platform.performHapticFeedback(type)
    }

    /** Extra high-density ticks for continuous value controls. */
    fun performDenseHapticFeedback(type: HapticFeedbackType) {
        if (settings.enabled && settings.density == TerminalHapticDensity.HIGH) {
            platform.performHapticFeedback(type)
        }
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
            if (key in setOf(TerminalHapticPreferences.KEY_ENABLED, TerminalHapticPreferences.KEY_DENSITY, TerminalHapticPreferences.KEY_SCROLL)) {
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
