package com.termux.view;

import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.List;

/**
 * An ordered list of terminal fonts used for glyph fallback.
 *
 * <p>Each custom face carries the Unicode ranges read from its font file. This is intentionally
 * not based only on {@code Paint.hasGlyph()}: Android may include its system fallback in that
 * check, which could hide a later custom font in this list.</p>
 */
public final class TerminalFontFamily {

    /** A typeface and the sorted inclusive Unicode ranges provided by its font file. */
    public static final class FontFace {
        private final Typeface mTypeface;
        private final int[] mCodePointRanges;
        private final float mScale;
        private final float mBaselineOffsetEm;

        public FontFace(Typeface typeface, int[] codePointRanges) {
            this(typeface, codePointRanges, 1.0f, 0.0f);
        }

        public FontFace(Typeface typeface, int[] codePointRanges, float scale,
                        float baselineOffsetEm) {
            if (typeface == null) throw new IllegalArgumentException("typeface must not be null");
            if (codePointRanges == null || (codePointRanges.length & 1) != 0)
                throw new IllegalArgumentException("codePointRanges must contain start/end pairs");
            int previousEnd = -1;
            for (int index = 0; index < codePointRanges.length; index += 2) {
                int rangeStart = codePointRanges[index];
                int rangeEnd = codePointRanges[index + 1];
                if (rangeStart <= previousEnd || rangeStart < 0 || rangeEnd < rangeStart ||
                    rangeEnd > Character.MAX_CODE_POINT)
                    throw new IllegalArgumentException("codePointRanges must be sorted, disjoint Unicode ranges");
                previousEnd = rangeEnd;
            }
            if (!(scale > 0.0f) || Float.isInfinite(scale))
                throw new IllegalArgumentException("scale must be finite and greater than zero");
            if (Float.isNaN(baselineOffsetEm) || Float.isInfinite(baselineOffsetEm))
                throw new IllegalArgumentException("baselineOffsetEm must be finite");
            mTypeface = typeface;
            mCodePointRanges = codePointRanges.clone();
            mScale = scale;
            mBaselineOffsetEm = baselineOffsetEm;
        }

        private boolean supports(int codePoint) {
            int low = 0;
            int high = mCodePointRanges.length / 2 - 1;
            while (low <= high) {
                int middle = (low + high) >>> 1;
                int start = mCodePointRanges[middle * 2];
                int end = mCodePointRanges[middle * 2 + 1];
                if (codePoint < start) {
                    high = middle - 1;
                } else if (codePoint > end) {
                    low = middle + 1;
                } else {
                    return true;
                }
            }
            return false;
        }
    }

    private final FontFace[] mCustomFaces;
    private final Typeface[][] mStyledTypefaces;
    private final FontFace[] mItalicCustomFaces;
    private final Typeface[][] mItalicStyledTypefaces;
    private final Typeface mPrimaryTypeface;

    /** Create a single-font family. Primarily retained for the public TerminalView API. */
    public TerminalFontFamily(Typeface regular) {
        mCustomFaces = new FontFace[0];
        mPrimaryTypeface = regular == null ? Typeface.MONOSPACE : regular;
        mStyledTypefaces = new Typeface[][] { createStyles(mPrimaryTypeface) };
        mItalicCustomFaces = new FontFace[0];
        mItalicStyledTypefaces = new Typeface[0][];
    }

    /** Create an ordered custom font family. Android monospace is always the final fallback. */
    public TerminalFontFamily(List<FontFace> customFaces) {
        this(customFaces, Typeface.MONOSPACE);
    }

    /** Create an ordered custom font family with the supplied final system fallback. */
    public TerminalFontFamily(List<FontFace> customFaces, Typeface fallback) {
        this(customFaces, null, fallback);
    }

    /** Create a family with independently ordered regular and actual italic font files. */
    public TerminalFontFamily(List<FontFace> customFaces, List<FontFace> italicCustomFaces, Typeface fallback) {
        List<FontFace> validFaces = validFaces(customFaces);
        mCustomFaces = validFaces.toArray(new FontFace[0]);
        List<FontFace> validItalicFaces = validFaces(italicCustomFaces);
        mItalicCustomFaces = validItalicFaces.toArray(new FontFace[0]);
        Typeface resolvedFallback = fallback == null ? Typeface.MONOSPACE : fallback;
        mPrimaryTypeface = mCustomFaces.length == 0 ? resolvedFallback : mCustomFaces[0].mTypeface;
        mStyledTypefaces = new Typeface[mCustomFaces.length + 1][];
        for (int i = 0; i < mCustomFaces.length; i++)
            mStyledTypefaces[i] = createStyles(mCustomFaces[i].mTypeface);
        mStyledTypefaces[mCustomFaces.length] = createStyles(resolvedFallback);
        mItalicStyledTypefaces = new Typeface[mItalicCustomFaces.length][];
        for (int i = 0; i < mItalicCustomFaces.length; i++)
            mItalicStyledTypefaces[i] = createItalicStyles(mItalicCustomFaces[i].mTypeface);
    }

    private static List<FontFace> validFaces(List<FontFace> faces) {
        List<FontFace> validFaces = new ArrayList<>();
        if (faces != null) {
            for (FontFace face : faces) {
                if (face != null && face.mCodePointRanges.length > 0) validFaces.add(face);
            }
        }
        return validFaces;
    }

    private static Typeface[] createStyles(Typeface base) {
        return new Typeface[] {
            base,
            Typeface.create(base, Typeface.BOLD),
            Typeface.create(base, Typeface.ITALIC),
            Typeface.create(base, Typeface.BOLD_ITALIC)
        };
    }

    private static Typeface[] createItalicStyles(Typeface base) {
        return new Typeface[] { base, Typeface.create(base, Typeface.BOLD_ITALIC) };
    }

    public Typeface getPrimaryTypeface() {
        return mPrimaryTypeface;
    }

    float getScale(Typeface typeface) {
        FontFace face = customFace(typeface);
        return face == null ? 1.0f : face.mScale;
    }

    float getBaselineOffsetEm(Typeface typeface) {
        FontFace face = customFace(typeface);
        return face == null ? 0.0f : face.mBaselineOffsetEm;
    }

    private FontFace customFace(Typeface typeface) {
        for (int faceIndex = 0; faceIndex < mCustomFaces.length; faceIndex++) {
            for (Typeface styledTypeface : mStyledTypefaces[faceIndex]) {
                if (styledTypeface == typeface) return mCustomFaces[faceIndex];
            }
        }
        for (int faceIndex = 0; faceIndex < mItalicCustomFaces.length; faceIndex++) {
            for (Typeface styledTypeface : mItalicStyledTypefaces[faceIndex]) {
                if (styledTypeface == typeface) return mItalicCustomFaces[faceIndex];
            }
        }
        return null;
    }

    /** Return the first custom font that covers every code point in the supplied glyph cluster. */
    public Typeface getTypefaceForText(char[] text, int start, int length, boolean bold, boolean italic) {
        if (italic) {
            for (int faceIndex = 0; faceIndex < mItalicCustomFaces.length; faceIndex++) {
                if (supportsAll(mItalicCustomFaces[faceIndex], text, start, length))
                    return mItalicStyledTypefaces[faceIndex][bold ? 1 : 0];
            }
        }
        int selectedFace = mCustomFaces.length; // System monospace fallback.
        for (int faceIndex = 0; faceIndex < mCustomFaces.length; faceIndex++) {
            if (supportsAll(mCustomFaces[faceIndex], text, start, length)) {
                selectedFace = faceIndex;
                break;
            }
        }
        int style = (bold ? 1 : 0) | (italic ? 2 : 0);
        return mStyledTypefaces[selectedFace][style];
    }

    private boolean supportsAll(FontFace face, char[] text, int start, int length) {
        int end = start + length;
        for (int index = start; index < end; ) {
            int codePoint = Character.codePointAt(text, index, end);
            if (!face.supports(codePoint) && !isShapingControl(codePoint)) return false;
            index += Character.charCount(codePoint);
        }
        return true;
    }

    /** Controls which modify a neighboring glyph do not need a standalone cmap glyph. */
    private boolean isShapingControl(int codePoint) {
        return codePoint == 0x200c || codePoint == 0x200d || codePoint == 0x2060 ||
            codePoint >= 0xfe00 && codePoint <= 0xfe0f ||
            codePoint >= 0xe0100 && codePoint <= 0xe01ef;
    }
}
