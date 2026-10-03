package com.termux.view;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TerminalGlyphPlacementTest {

    @Test
    public void centersVisualBoundsIncludingBearingsWithoutStretching() {
        TerminalGlyphPlacement.Result placement = TerminalGlyphPlacement.place(
            100.0f, 20.0f, 0.0f, 24.0f, 18.0f,
            -2.0f, -12.0f, 12.0f, 3.0f, 12.0f, 1.0f, 0.0f);

        assertEquals(105.0f, placement.originX, 0.001f);
        assertEquals(103.0f, placement.originX + placement.scale * -2.0f, 0.001f);
        assertEquals(117.0f, placement.originX + placement.scale * 12.0f, 0.001f);
        assertEquals(1.0f, placement.scale, 0.001f);
    }

    @Test
    public void uniformlyShrinksOnlyWhenVisualBoundsExceedTheSlot() {
        TerminalGlyphPlacement.Result placement = TerminalGlyphPlacement.place(
            0.0f, 20.0f, 0.0f, 30.0f, 22.0f,
            0.0f, -20.0f, 30.0f, 5.0f, 30.0f, 1.0f, 0.0f);

        assertEquals(2.0f / 3.0f, placement.scale, 0.001f);
        assertEquals(0.0f, placement.originX, 0.001f);
    }

    @Test
    public void appliesBaselineOffsetAndClampsToTheRow() {
        TerminalGlyphPlacement.Result placement = TerminalGlyphPlacement.place(
            0.0f, 20.0f, 0.0f, 20.0f, 15.0f,
            0.0f, -20.0f, 10.0f, 10.0f, 10.0f, 1.0f, 1.0f);

        assertEquals(16.0f, placement.baselineY, 0.001f);
        assertEquals(0.4f, placement.scale, 0.001f);
    }

    @Test
    public void selectionOfEitherHalfIntersectsAWideGlyph() {
        assertTrue(TerminalGlyphPlacement.intersectsSelection(4, 2, 4, 4));
        assertTrue(TerminalGlyphPlacement.intersectsSelection(4, 2, 5, 5));
        assertFalse(TerminalGlyphPlacement.intersectsSelection(4, 2, 6, 6));
    }

    @Test
    public void renderOptionsPreserveNaturalWideGlyphsByDefault() {
        TerminalGlyphRenderOptions defaults = TerminalGlyphRenderOptions.DEFAULT;
        assertTrue(defaults.naturalWideGlyphs());
        assertEquals(1.0f, defaults.fallbackScale(), 0.0f);
        assertEquals(0.0f, defaults.fallbackBaselineOffsetEm(), 0.0f);
        assertFalse(defaults.withNaturalWideGlyphs(false).naturalWideGlyphs());
    }
}
