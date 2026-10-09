@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.terminal.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import dev.terminal.app.LocalServicesActivity
import dev.terminal.app.R

/** Shared anchor and navigation for both session headers and the empty terminal. */
@Composable
internal fun TerminalOverflowMenu(onSettings: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, tint: Color = MaterialTheme.colorScheme.onBackground) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val haptics = LocalTerminalHaptics.current
    Box(modifier) {
        IconButton(onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
            expanded = true
        }, enabled = enabled, shapes = IconButtonDefaults.shapes()) {
            Icon(painterResource(R.drawable.ic_material_symbol_more_vert_24), stringResource(R.string.terminal_more), tint = tint)
        }
        DropdownMenuPopup(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1),
                modifier = Modifier.verticalScroll(rememberScrollState())) {
                DropdownMenuItem(text = { Text(stringResource(R.string.local_services_title)) },
                    shape = MenuDefaults.leadingItemShape,
                    leadingIcon = { Icon(painterResource(R.drawable.ic_material_symbol_lan_24), null,
                        modifier = Modifier.size(MenuDefaults.LeadingIconSize)) },
                    onClick = { expanded = false; context.startActivity(Intent(context, LocalServicesActivity::class.java)) })
                DropdownMenuItem(text = { Text(stringResource(R.string.settings)) },
                    shape = MenuDefaults.trailingItemShape,
                    leadingIcon = { Icon(painterResource(R.drawable.ic_material_symbol_settings_24), null,
                        modifier = Modifier.size(MenuDefaults.LeadingIconSize)) },
                    onClick = { expanded = false; onSettings() })
            }
        }
    }
}
