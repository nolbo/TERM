package dev.terminal.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomTerminalFontsTest {
    @Test fun customFontsRoundTripInPriorityOrderAndDeduplicateUris() {
        val fonts = listOf(
            CustomTerminalFont("content://fonts/first", "First.ttf"),
            CustomTerminalFont("content://fonts/second", "Second.otf"),
            CustomTerminalFont("content://fonts/first", "Duplicate.ttf"),
        )

        assertEquals(fonts.take(2), parseCustomTerminalFonts(serializeCustomTerminalFonts(fonts)))
    }

    @Test fun malformedEntriesAreIgnoredWithoutChangingOtherPriorities() {
        val parsed = parseCustomTerminalFonts("""[{"uri":"content://fonts/a","name":"A.ttf"},{"name":"missing"},false,{"uri":"content://fonts/b","name":"B.ttf"}]""")

        assertEquals(listOf("content://fonts/a", "content://fonts/b"), parsed.map { it.uri })
    }

    @Test fun italicFontsRoundTripSeparatelyFromRegularFonts() {
        val fonts = listOf(
            CustomTerminalFont("content://fonts/regular", "Regular.ttf"),
            CustomTerminalFont("content://fonts/italic", "Italic.ttf", CustomTerminalFontStyle.ITALIC),
        )

        assertEquals(fonts, parseCustomTerminalFonts(serializeCustomTerminalFonts(fonts)))
        assertEquals(CustomTerminalFontStyle.REGULAR, parseCustomTerminalFonts("""[{"uri":"content://fonts/old","name":"Old.ttf"}]""").single().style)
    }

    @Test fun moveChangesOnlyTheRequestedPriority() {
        val fonts = listOf(
            CustomTerminalFont("a", "A"),
            CustomTerminalFont("b", "B"),
            CustomTerminalFont("c", "C"),
        )

        assertEquals(listOf("b", "a", "c"), moveCustomTerminalFont(fonts, 1, 0).map { it.uri })
        assertTrue(moveCustomTerminalFont(fonts, -1, 1) === fonts)
    }
}
