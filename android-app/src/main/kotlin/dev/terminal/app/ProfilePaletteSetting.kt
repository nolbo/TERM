@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.terminal.app.ui.terminalListItemShapes
import dev.terminal.app.ui.terminalSettingsListItemColors
import dev.terminal.runtime.session.SessionProfile

@Composable
internal fun ProfilePaletteSetting(palette: List<String>?, others: List<SessionProfile>, change: (List<String>?) -> Unit) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences(SettingsDetailActivity.TERMINAL_PREFERENCES, Context.MODE_PRIVATE) }
    var importing by rememberSaveable { mutableStateOf(false) }
    val editor = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(PaletteEditorActivity.EXTRA_PALETTE)?.let { colors ->
                change(TerminalAppearance.fromPaletteList(colors).toList())
            }
        }
    }
    PaletteAction(R.string.customize_palette,
        stringResource(if (palette == null) R.string.profile_palette_global else R.string.profile_palette_custom)) {
        editor.launch(Intent(context, PaletteEditorActivity::class.java)
            .putExtra(PaletteEditorActivity.EXTRA_RETURN_PALETTE, true)
            .putStringArrayListExtra(PaletteEditorActivity.EXTRA_PALETTE,
                ArrayList(palette ?: TerminalAppearance.paletteColors(preferences).toList())))
    }
    PaletteAction(R.string.profile_palette_copy_global, stringResource(R.string.profile_palette_copy_hint)) {
        change(TerminalAppearance.paletteColors(preferences).toList())
    }
    PaletteAction(R.string.profile_palette_import, stringResource(R.string.profile_palette_import_hint), others.isNotEmpty()) {
        importing = true
    }
    PaletteAction(R.string.profile_palette_reset, stringResource(R.string.profile_palette_reset_hint), palette != null) {
        change(null)
    }
    if (importing) AlertDialog(onDismissRequest = { importing = false },
        title = { Text(stringResource(R.string.profile_palette_import)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                others.forEach { profile ->
                    TextButton(onClick = {
                        // Resolve at the moment of import, including sources that inherit global colors.
                        val current = SessionProfiles(context).find(profile.id) ?: profile
                        change(TerminalAppearance.profilePalette(preferences, current).toList())
                        importing = false
                    }, modifier = Modifier.fillMaxWidth()) { Text(profile.name) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { importing = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun PaletteAction(label: Int, summary: String, enabled: Boolean = true, action: () -> Unit) {
    ListItem(onClick = action, enabled = enabled,
        supportingContent = { Text(summary) }, shapes = terminalListItemShapes(),
        colors = terminalSettingsListItemColors(), modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(label)) }
}
