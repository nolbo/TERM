package dev.terminal.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/** HSV remains authoritative during gestures, including hue at zero saturation/value. */
internal data class PalettePickerState(
    val hue: Float,
    val saturation: Float,
    val brightness: Float,
    val hexDraft: String,
    val redDraft: String,
    val greenDraft: String,
    val blueDraft: String,
) {
    val rgb: Int get() = Color.hsv(hue, saturation, brightness).toArgb() and 0xFFFFFF
    val hex: String get() = "#%06X".format(rgb)
    val validHex: Boolean get() = parseHex(hexDraft) != null
    val validRgb: Boolean get() = listOf(redDraft, greenDraft, blueDraft).all { parseChannel(it) != null }
    val canApply: Boolean get() = validHex && validRgb

    fun changeHsv(h: Float = hue, s: Float = saturation, v: Float = brightness): PalettePickerState {
        val next = copy(hue = h.coerceIn(0f, 360f), saturation = s.coerceIn(0f, 1f), brightness = v.coerceIn(0f, 1f))
        return next.syncDrafts(next.rgb)
    }

    fun editHex(text: String): PalettePickerState = parseHex(text)?.let { fromRgb(it).copy(hexDraft = text) }
        ?: copy(hexDraft = text)

    fun editRgb(channel: Int, text: String): PalettePickerState {
        require(channel in 0..2)
        val next = when (channel) {
            0 -> copy(redDraft = text)
            1 -> copy(greenDraft = text)
            else -> copy(blueDraft = text)
        }
        val r = parseChannel(next.redDraft) ?: return next
        val g = parseChannel(next.greenDraft) ?: return next
        val b = parseChannel(next.blueDraft) ?: return next
        return fromRgb((r shl 16) or (g shl 8) or b).copy(
            redDraft = next.redDraft, greenDraft = next.greenDraft, blueDraft = next.blueDraft,
        )
    }

    private fun syncDrafts(rgb: Int) = copy(
        hexDraft = "#%06X".format(rgb), redDraft = (rgb shr 16).toString(),
        greenDraft = ((rgb shr 8) and 255).toString(), blueDraft = (rgb and 255).toString(),
    )

    private fun fromRgb(rgb: Int): PalettePickerState {
        val r = (rgb shr 16) / 255f
        val g = ((rgb shr 8) and 255) / 255f
        val b = (rgb and 255) / 255f
        val max = maxOf(r, g, b)
        val delta = max - minOf(r, g, b)
        val h = when {
            delta == 0f -> hue
            max == r -> ((g - b) / delta * 60f + 360f) % 360f
            max == g -> ((b - r) / delta + 2f) * 60f
            else -> ((r - g) / delta + 4f) * 60f
        }
        return copy(hue = h, saturation = if (max == 0f) saturation else delta / max, brightness = max)
            .syncDrafts(rgb)
    }

    companion object {
        fun fromHex(hex: String): PalettePickerState = PalettePickerState(0f, 0f, 0f, "", "", "", "")
            .fromRgb(requireNotNull(parseHex(hex)))

        fun parseHex(text: String): Int? = text.trim().removePrefix("#")
            .takeIf { it.length == 6 && it.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' } }
            ?.toIntOrNull(16)

        fun parseChannel(text: String): Int? = text.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }
            ?.toIntOrNull()?.takeIf { it in 0..255 }
    }
}
