@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.terminal.app.ui.SettingsPageScaffold
import dev.terminal.app.ui.TerminalActivity
import dev.terminal.app.ui.terminalListItemShapes

/** Full-screen draft: no terminal palette changes until the user saves all 19 colors. */
class PaletteEditorActivity : TerminalActivity() {
    companion object {
        const val EXTRA_PALETTE = "palette"
        const val EXTRA_RETURN_PALETTE = "returnPalette"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences(SettingsDetailActivity.TERMINAL_PREFERENCES, MODE_PRIVATE)
        val initial = intent.getStringArrayListExtra(EXTRA_PALETTE)?.let(TerminalAppearance::fromPaletteList)
            ?: TerminalAppearance.paletteColors(preferences)
        val original = listOf(initial.foreground, initial.background, initial.cursor) + initial.ansi
        compose {
            var draft by rememberSaveable { mutableStateOf(original.joinToString(",")) }
            val colors = draft.split(',')
            var selected by rememberSaveable { mutableIntStateOf(-1) }
            var discard by rememberSaveable { mutableStateOf(false) }
            val changed = colors != original
            val leave = { if (changed) discard = true else finish() }
            BackHandler { leave() }
            SettingsPageScaffold(stringResource(R.string.customize_palette), leave) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    LazyColumn(
                        Modifier.weight(1f),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        item {
                            Text(stringResource(R.string.palette_editor_hint),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyLarge)
                        }
                        item { PalettePreview(colors) }
                        item { PaletteGroup(R.string.palette_main_colors, colors, 0..2) { selected = it } }
                        item { PaletteGroup(R.string.palette_standard_colors, colors, 3..10) { selected = it } }
                        item { PaletteGroup(R.string.palette_bright_colors, colors, 11..18) { selected = it } }
                    }
                    Button(
                        onClick = {
                            if (intent.getBooleanExtra(EXTRA_RETURN_PALETTE, false)) {
                                setResult(RESULT_OK, android.content.Intent().putStringArrayListExtra(EXTRA_PALETTE, ArrayList(colors)))
                            } else TerminalAppearance.saveCustomPalette(preferences, TerminalAppearance.fromPaletteList(colors))
                            finish()
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(56.dp),
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(stringResource(R.string.save)) }
                }
            }
            if (selected >= 0) {
                val index = selected
                ModalBottomSheet(
                    onDismissRequest = { selected = -1 },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                ) {
                    PaletteColorPicker(
                        label = stringResource(paletteLabel(index)),
                        initialHex = colors[index],
                        onCancel = { selected = -1 },
                        onSelect = { hex ->
                            draft = colors.toMutableList().apply { this[index] = hex }.joinToString(",")
                            selected = -1
                        },
                    )
                }
            }
            if (discard) {
                AlertDialog(
                    onDismissRequest = { discard = false },
                    title = { Text(stringResource(R.string.palette_discard_title)) },
                    text = { Text(stringResource(R.string.palette_discard_message)) },
                    confirmButton = { TextButton(onClick = { finish() }) { Text(stringResource(R.string.palette_discard)) } },
                    dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.cancel)) } },
                )
            }
        }
    }
}

@Composable
private fun PalettePreview(colors: List<String>) {
    Column(
        Modifier.fillMaxWidth().background(hexColor(colors[1]), RoundedCornerShape(28.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.palette_preview), color = hexColor(colors[0]), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            colors.drop(3).take(8).forEach { hex ->
                Box(Modifier.weight(1f).height(12.dp).background(hexColor(hex), CircleShape))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            colors.drop(11).forEach { hex ->
                Box(Modifier.weight(1f).height(12.dp).background(hexColor(hex), CircleShape))
            }
        }
        Text("$ echo TERM", color = hexColor(colors[0]), style = MaterialTheme.typography.bodyMedium)
        Box(Modifier.size(width = 12.dp, height = 18.dp).background(hexColor(colors[2])))
    }
}

@Composable
private fun PaletteGroup(@StringRes title: Int, colors: List<String>, indices: IntRange, onEdit: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(title), modifier = Modifier.padding(start = 4.dp), style = MaterialTheme.typography.titleMedium)
        Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(28.dp)).padding(8.dp)) {
            indices.forEach { index ->
                ListItem(
                    supportingContent = { Text(colors[index]) },
                    leadingContent = {
                        Box(Modifier.size(40.dp).background(hexColor(colors[index]), CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
                    },
                    onClick = { onEdit(index) },
                    shapes = terminalListItemShapes(),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.fillMaxWidth(),
                    content = { Text(stringResource(paletteLabel(index))) },
                )
            }
        }
    }
}

private fun hexColor(hex: String) = Color(android.graphics.Color.parseColor(hex))

@StringRes
private fun paletteLabel(index: Int): Int = when (index) {
    0 -> R.string.palette_foreground
    1 -> R.string.palette_background
    2 -> R.string.palette_cursor
    in 3..18 -> ANSI_LABELS[index - 3]
    else -> error("Unsupported palette index: $index")
}

private val ANSI_LABELS = intArrayOf(
    R.string.palette_ansi_black, R.string.palette_ansi_red, R.string.palette_ansi_green, R.string.palette_ansi_yellow,
    R.string.palette_ansi_blue, R.string.palette_ansi_magenta, R.string.palette_ansi_cyan, R.string.palette_ansi_white,
    R.string.palette_ansi_bright_black, R.string.palette_ansi_bright_red, R.string.palette_ansi_bright_green,
    R.string.palette_ansi_bright_yellow, R.string.palette_ansi_bright_blue, R.string.palette_ansi_bright_magenta,
    R.string.palette_ansi_bright_cyan, R.string.palette_ansi_bright_white,
)
