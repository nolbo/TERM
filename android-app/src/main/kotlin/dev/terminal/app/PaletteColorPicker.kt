@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private val PickerSaver = listSaver<PalettePickerState, Any>(
    save = { listOf(it.hue, it.saturation, it.brightness, it.hexDraft, it.redDraft, it.greenDraft, it.blueDraft) },
    restore = { PalettePickerState(it[0] as Float, it[1] as Float, it[2] as Float,
        it[3] as String, it[4] as String, it[5] as String, it[6] as String) },
)

/** Shared by global and profile palettes; edits stay local until Apply. */
@Composable
internal fun PaletteColorPicker(label: String, initialHex: String, onCancel: () -> Unit, onSelect: (String) -> Unit) {
    var state by rememberSaveable(initialHex, stateSaver = PickerSaver) { mutableStateOf(PalettePickerState.fromHex(initialHex)) }
    val selectedColor = Color(0xFF000000.toInt() or state.rgb)
    val hueLabel = stringResource(R.string.palette_hue)
    Column(
        Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            Box(Modifier.size(40.dp).background(selectedColor, CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
        }
        Text(stringResource(R.string.palette_sv_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            SaturationBrightnessPlane(state, { s, v -> state = state.changeHsv(s = s, v = v) },
                Modifier.widthIn(max = 300.dp).fillMaxWidth().aspectRatio(1f))
        }
        Column {
            Text("$hueLabel · ${state.hue.roundToInt()}°", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = state.hue, onValueChange = { state = state.changeHsv(h = it) }, valueRange = 0f..360f,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = hueLabel },
                thumb = {
                    Box(Modifier.size(24.dp).background(Color.hsv(state.hue, 1f, 1f), CircleShape)
                        .border(2.dp, Color.White, CircleShape))
                },
                track = {
                    Canvas(Modifier.fillMaxWidth().height(16.dp)) {
                        drawRoundRect(Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f, 1f, 1f) }),
                            cornerRadius = CornerRadius(8.dp.toPx()))
                    }
                },
            )
        }
        OutlinedTextField(
            value = state.hexDraft, onValueChange = { state = state.editHex(it) },
            label = { Text(stringResource(R.string.palette_hex_label)) },
            supportingText = { Text(stringResource(if (state.validHex) R.string.palette_hex_support else R.string.palette_hex_error)) },
            isError = !state.validHex, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth(),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(state.redDraft, state.greenDraft, state.blueDraft).forEachIndexed { index, draft ->
                    val channelLabel = stringResource(listOf(R.string.palette_rgb_red, R.string.palette_rgb_green, R.string.palette_rgb_blue)[index])
                    OutlinedTextField(
                        value = draft, onValueChange = { state = state.editRgb(index, it) },
                        label = { Text(listOf("R", "G", "B")[index]) },
                        isError = PalettePickerState.parseChannel(draft) == null, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f).semantics { contentDescription = channelLabel },
                    )
                }
            }
            Text(stringResource(R.string.palette_rgb_support), style = MaterialTheme.typography.bodySmall,
                color = if (state.validRgb) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            TextButton(onClick = onCancel, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) }
            Button(onClick = { onSelect(state.hex) }, enabled = state.canApply, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.palette_apply_color))
            }
        }
    }
}

@Composable
private fun SaturationBrightnessPlane(
    state: PalettePickerState,
    onChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val change by rememberUpdatedState(onChange)
    val focus = remember { FocusRequester() }
    val description = stringResource(R.string.palette_sv_hint)
    val currentValue = stringResource(R.string.palette_sv_value,
        (state.saturation * 100).roundToInt(), (state.brightness * 100).roundToInt())
    val moreSaturation = stringResource(R.string.palette_more_saturation)
    val lessSaturation = stringResource(R.string.palette_less_saturation)
    val moreBrightness = stringResource(R.string.palette_more_brightness)
    val lessBrightness = stringResource(R.string.palette_less_brightness)
    val outline = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier
        .focusRequester(focus)
        .onKeyEvent {
            if (it.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (it.key) {
                Key.DirectionRight -> change((state.saturation + 0.01f).coerceAtMost(1f), state.brightness)
                Key.DirectionLeft -> change((state.saturation - 0.01f).coerceAtLeast(0f), state.brightness)
                Key.DirectionUp -> change(state.saturation, (state.brightness + 0.01f).coerceAtMost(1f))
                Key.DirectionDown -> change(state.saturation, (state.brightness - 0.01f).coerceAtLeast(0f))
                else -> return@onKeyEvent false
            }
            true
        }
        .semantics {
            contentDescription = description
            stateDescription = currentValue
            customActions = listOf(
                CustomAccessibilityAction(moreSaturation) { change((state.saturation + 0.05f).coerceAtMost(1f), state.brightness); true },
                CustomAccessibilityAction(lessSaturation) { change((state.saturation - 0.05f).coerceAtLeast(0f), state.brightness); true },
                CustomAccessibilityAction(moreBrightness) { change(state.saturation, (state.brightness + 0.05f).coerceAtMost(1f)); true },
                CustomAccessibilityAction(lessBrightness) { change(state.saturation, (state.brightness - 0.05f).coerceAtLeast(0f)); true },
            )
        }
        .focusable()
        .clip(RoundedCornerShape(12.dp))
        .border(1.dp, outline, RoundedCornerShape(12.dp))
        .pointerInput(Unit) {
            // Consume from down so the sheet and its vertical scroller do not steal a color drag.
            awaitEachGesture {
                val down = awaitFirstDown()
                focus.requestFocus()
                fun update(position: Offset) {
                    val inset = 10.dp.toPx()
                    val width = (size.width - 2 * inset).coerceAtLeast(1f)
                    val height = (size.height - 2 * inset).coerceAtLeast(1f)
                    change(((position.x - inset) / width).coerceIn(0f, 1f),
                        (1f - (position.y - inset) / height).coerceIn(0f, 1f))
                }
                down.consume()
                update(down.position)
                drag(down.id) { event -> update(event.position); event.consume() }
            }
        }) {
        // Leave room for the handle at the extrema without losing access to pure white/black.
        val inset = 10.dp.toPx()
        drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(state.hue, 1f, 1f)),
            startX = inset, endX = size.width - inset))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black),
            startY = inset, endY = size.height - inset))
        val center = Offset(inset + state.saturation * (size.width - 2 * inset),
            inset + (1f - state.brightness) * (size.height - 2 * inset))
        drawCircle(Color.Black.copy(alpha = 0.75f), 8.dp.toPx(), center, style = Stroke(4.dp.toPx()))
        drawCircle(Color.White, 8.dp.toPx(), center, style = Stroke(2.dp.toPx()))
    }
}
