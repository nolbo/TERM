package dev.terminal.app.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class TerminalSheetBackdropTest {
    private val warm = Color(0xFF301B26)
    private val cool = Color(0xFF163025)

    @Test fun noVisibleCanvasUsesTheCallersFallback() {
        assertNull(terminalSheetCorners(emptyList()))
    }

    @Test fun singleCanvasContinuesIntoBothCorners() {
        assertEquals(TerminalSheetCorners(warm, warm),
            terminalSheetCorners(listOf(TerminalCanvasSurface(10, 990, 700, warm))))
    }

    @Test fun stackedPanesUseOnlyTheBottomCanvas() {
        val top = TerminalCanvasSurface(10, 990, 240, warm)
        val bottom = TerminalCanvasSurface(10, 990, 700, cool)
        for (surfaces in listOf(listOf(top, bottom), listOf(bottom, top)))
            assertEquals(TerminalSheetCorners(cool, cool), terminalSheetCorners(surfaces))
    }

    @Test fun unequalSideBySidePanesKeepEachPhysicalCorner() {
        val left = TerminalCanvasSurface(10, 210, 700, warm)
        val right = TerminalCanvasSurface(222, 990, 700, cool)
        for (surfaces in listOf(listOf(left, right), listOf(right, left)))
            assertEquals(TerminalSheetCorners(warm, cool), terminalSheetCorners(surfaces))
    }

    @Test fun physicalPlacementWinsOverLogicalPaneOrder() {
        val firstOnRight = TerminalCanvasSurface(710, 990, 700, warm)
        val secondOnLeft = TerminalCanvasSurface(10, 698, 700, cool)
        assertEquals(TerminalSheetCorners(cool, warm),
            terminalSheetCorners(listOf(firstOnRight, secondOnLeft)))
    }
}
