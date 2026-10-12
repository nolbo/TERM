package com.termux.view;

/** Pure geometry for fitting a glyph into an immutable terminal-cell slot. */
final class TerminalGlyphPlacement {

    static final class Result {
        final float originX;
        final float baselineY;
        final float scale;

        Result(float originX, float baselineY, float scale) {
            this.originX = originX;
            this.baselineY = baselineY;
            this.scale = scale;
        }
    }

    private TerminalGlyphPlacement() {}

    static Result place(float slotLeft, float slotWidth, float rowTop, float rowBottom,
                        float primaryBaseline, float visualLeft, float visualTop,
                        float visualRight, float visualBottom, float measuredAdvance,
                        float requestedScale, float baselineOffset) {
        float effectiveLeft = visualLeft;
        float effectiveRight = visualRight;
        if (!(effectiveRight > effectiveLeft) && measuredAdvance > 0.0f) {
            effectiveLeft = 0.0f;
            effectiveRight = measuredAdvance;
        }

        float baseline = primaryBaseline + baselineOffset;
        float scale = requestedScale;
        float visualWidth = effectiveRight - effectiveLeft;
        if (visualWidth > 0.0f) scale = Math.min(scale, slotWidth / visualWidth);

        if (visualTop < 0.0f) {
            float roomAboveBaseline = Math.max(0.0f, baseline - rowTop);
            scale = Math.min(scale, roomAboveBaseline / -visualTop);
        }
        if (visualBottom > 0.0f) {
            float roomBelowBaseline = Math.max(0.0f, rowBottom - baseline);
            scale = Math.min(scale, roomBelowBaseline / visualBottom);
        }

        float visualCenter = (effectiveLeft + effectiveRight) * 0.5f;
        float origin = slotLeft + slotWidth * 0.5f - visualCenter * scale;
        return new Result(origin, baseline, Math.max(0.0f, scale));
    }

    static boolean intersectsSelection(int column, int width, int selectionStart, int selectionEnd) {
        if (selectionStart < 0 || selectionEnd < selectionStart) return false;
        int occupiedWidth = Math.max(1, width);
        return column <= selectionEnd && column + occupiedWidth - 1 >= selectionStart;
    }
}
