package com.termux.view;

/**
 * Visual adjustments for glyphs which occupy two terminal cells.
 *
 * <p>These values affect drawing only. They never change Unicode width, cursor movement, wrapping,
 * hit testing, or any other part of the terminal grid.</p>
 */
public final class TerminalGlyphRenderOptions {

    public static final TerminalGlyphRenderOptions DEFAULT =
        new TerminalGlyphRenderOptions(false, 1.0f, 0.0f);

    private final boolean mNaturalWideGlyphs;
    private final float mFallbackScale;
    private final float mFallbackBaselineOffsetEm;

    public TerminalGlyphRenderOptions(boolean naturalWideGlyphs, float fallbackScale,
                                      float fallbackBaselineOffsetEm) {
        if (!(fallbackScale > 0.0f) || Float.isInfinite(fallbackScale))
            throw new IllegalArgumentException("fallbackScale must be finite and greater than zero");
        if (Float.isNaN(fallbackBaselineOffsetEm) || Float.isInfinite(fallbackBaselineOffsetEm))
            throw new IllegalArgumentException("fallbackBaselineOffsetEm must be finite");
        mNaturalWideGlyphs = naturalWideGlyphs;
        mFallbackScale = fallbackScale;
        mFallbackBaselineOffsetEm = fallbackBaselineOffsetEm;
    }

    public boolean naturalWideGlyphs() {
        return mNaturalWideGlyphs;
    }

    public float fallbackScale() {
        return mFallbackScale;
    }

    public float fallbackBaselineOffsetEm() {
        return mFallbackBaselineOffsetEm;
    }

    public TerminalGlyphRenderOptions withNaturalWideGlyphs(boolean enabled) {
        if (enabled == mNaturalWideGlyphs) return this;
        return new TerminalGlyphRenderOptions(enabled, mFallbackScale, mFallbackBaselineOffsetEm);
    }
}
