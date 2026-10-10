@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.terminal.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import dev.terminal.app.R

/** Shared settings action for both session headers and the empty terminal. */
@Composable
internal fun TerminalSettingsButton(onSettings: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, tint: Color = MaterialTheme.colorScheme.onBackground) {
    val haptics = LocalTerminalHaptics.current
    Box(modifier) {
        IconButton(onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
            onSettings()
        }, enabled = enabled, shapes = IconButtonDefaults.shapes()) {
            Icon(painterResource(R.drawable.ic_material_symbol_settings_24), stringResource(R.string.settings), tint = tint)
        }
    }
}
