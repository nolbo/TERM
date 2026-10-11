package dev.terminal.runtime.session

import android.view.KeyEvent
import dev.terminal.emulator.KeyHandler
import dev.terminal.emulator.KittyKeyboard

internal object KittyRemoteKeys {
    fun encode(keys: List<String>, flags: Int): ByteArray {
        val keyboard = KittyKeyboard().apply { set(flags, 1) }
        return buildString {
            for (spec in keys) {
                val parts = spec.lowercase().split('+')
                var mods = 0
                for (mod in parts.dropLast(1)) mods = mods or when (mod) {
                    "shift" -> 1; "alt", "opt" -> 2; "ctrl", "control" -> 4
                    "super", "cmd" -> 8; "hyper" -> 16; "meta" -> 32
                    else -> throw IllegalArgumentException("Unknown key modifier")
                }
                val name = parts.last()
                val functional = functional(name)
                val cp = if (name == "space") 32 else if (name == "plus") 43 else name.codePoints().toArray().singleOrNull()
                require(functional != null || cp != null) { "Unknown key: $name" }
                val key = functional?.first ?: KittyKeyboard.Key.text(cp!!)
                val text = if (functional == null && mods and 62 == 0) String(Character.toChars(if (mods and 1 != 0) Character.toUpperCase(cp!!) else cp!!)) else null
                val press = keyboard.encode(key, mods, KittyKeyboard.PRESS, cp?.let(Character::toUpperCase) ?: 0, 0, text)
                if (press != null) append(press) else if (functional != null) {
                    val legacyMods = (if (mods and 1 != 0) KeyHandler.KEYMOD_SHIFT else 0) or
                        (if (mods and 2 != 0) KeyHandler.KEYMOD_ALT else 0) or (if (mods and 4 != 0) KeyHandler.KEYMOD_CTRL else 0)
                    require(mods and 56 == 0) { "Modifier requires negotiated kitty keyboard protocol" }
                    append(KeyHandler.getCode(functional.second, legacyMods, false, false) ?: error("Unsupported legacy key"))
                } else {
                    require(mods and 56 == 0) { "Modifier requires negotiated kitty keyboard protocol" }
                    if (mods and 2 != 0) append('\u001b')
                    val value = if (mods and 4 != 0) when (cp!!) {
                        in 97..122 -> cp - 96; 32, 64 -> 0; in 91..95 -> cp - 64; 63 -> 127
                        else -> throw IllegalArgumentException("Unsupported legacy control key")
                    } else if (mods and 1 != 0) Character.toUpperCase(cp!!) else cp!!
                    appendCodePoint(value)
                }
                keyboard.encode(key, mods, KittyKeyboard.RELEASE, 0, 0, null)?.let { append(it) }
            }
        }.toByteArray()
    }

    private fun functional(name: String): Pair<KittyKeyboard.Key, Int>? {
        val values = when (name) {
            "enter", "return" -> Triple(13, 'u', KeyEvent.KEYCODE_ENTER)
            "tab" -> Triple(9, 'u', KeyEvent.KEYCODE_TAB)
            "backspace" -> Triple(127, 'u', KeyEvent.KEYCODE_DEL)
            "escape", "esc" -> Triple(27, 'u', KeyEvent.KEYCODE_ESCAPE)
            "up" -> Triple(1, 'A', KeyEvent.KEYCODE_DPAD_UP)
            "down" -> Triple(1, 'B', KeyEvent.KEYCODE_DPAD_DOWN)
            "right" -> Triple(1, 'C', KeyEvent.KEYCODE_DPAD_RIGHT)
            "left" -> Triple(1, 'D', KeyEvent.KEYCODE_DPAD_LEFT)
            "home" -> Triple(1, 'H', KeyEvent.KEYCODE_MOVE_HOME)
            "end" -> Triple(1, 'F', KeyEvent.KEYCODE_MOVE_END)
            "insert" -> Triple(2, '~', KeyEvent.KEYCODE_INSERT)
            "delete" -> Triple(3, '~', KeyEvent.KEYCODE_FORWARD_DEL)
            "page_up" -> Triple(5, '~', KeyEvent.KEYCODE_PAGE_UP)
            "page_down" -> Triple(6, '~', KeyEvent.KEYCODE_PAGE_DOWN)
            else -> {
                val f = name.removePrefix("f").toIntOrNull()?.takeIf { name.startsWith('f') && it in 1..12 } ?: return null
                val n = intArrayOf(1, 1, 13, 1, 15, 17, 18, 19, 20, 21, 23, 24)[f - 1]
                Triple(n, when (f) { 1 -> 'P'; 2 -> 'Q'; 4 -> 'S'; else -> '~' }, KeyEvent.KEYCODE_F1 + f - 1)
            }
        }
        return KittyKeyboard.Key(values.first, values.second, true, false) to values.third
    }
}
