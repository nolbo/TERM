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

        public FontFace(Typeface typeface, int[] codePointRanges) {
            if (typeface == null) throw new IllegalArgumentException("typeface must not be null");
            if (codePointRanges == null || (codePointRanges.length & 1) != 0)
                throw new IllegalArgumentException("codePointRanges must contain start/end pairs");
            mTypeface = typeface;
            mCodePointRanges = codePointRanges.clone();
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
    private final Typeface mPrimaryTypeface;

    /** Create a single-font family. Primarily retained for the public TerminalView API. */
    public TerminalFontFamily(Typeface regular) {
        mCustomFaces = new FontFace[0];
        mPrimaryTypeface = regular == null ? Typeface.MONOSPACE : regular;
        mStyledTypefaces = new Typeface[][] { createStyles(mPrimaryTypeface) };
    }

    /** Create an ordered custom font family. Android monospace is always the final fallback. */
    public TerminalFontFamily(List<FontFace> customFaces) {
        List<FontFace> validFaces = new ArrayList<>();
        if (customFaces != null) {
            for (FontFace face : customFaces) {
                if (face != null && face.mCodePointRanges.length > 0) validFaces.add(face);
            }
        }

        mCustomFaces = validFaces.toArray(new FontFace[0]);
        mPrimaryTypeface = mCustomFaces.length == 0 ? Typeface.MONOSPACE : mCustomFaces[0].mTypeface;
        mStyledTypefaces = new Typeface[mCustomFaces.length + 1][];
        for (int i = 0; i < mCustomFaces.length; i++)
            mStyledTypefaces[i] = createStyles(mCustomFaces[i].mTypeface);
        mStyledTypefaces[mCustomFaces.length] = createStyles(Typeface.MONOSPACE);
    }

    private static Typeface[] createStyles(Typeface base) {
        return new Typeface[] {
            base,
            Typeface.create(base, Typeface.BOLD),
            Typeface.create(base, Typeface.ITALIC),
            Typeface.create(base, Typeface.BOLD_ITALIC)
        };
    }

    public Typeface getPrimaryTypeface() {
        return mPrimaryTypeface;
    }

    /** Return the first custom font that covers every code point in the supplied glyph cluster. */
    public Typeface getTypefaceForText(char[] text, int start, int length, boolean bold, boolean italic) {
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
            if (!face.supports(codePoint)) return false;
            index += Character.charCount(codePoint);
        }
        return true;
    }
}
