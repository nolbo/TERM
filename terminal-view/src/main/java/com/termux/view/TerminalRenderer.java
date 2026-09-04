package com.termux.view;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Typeface;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.TextStyle;
import com.termux.terminal.WcWidth;

/**
 * Renderer of a {@link TerminalEmulator} into a {@link Canvas}.
 * <p/>
 * Saves font metrics, so needs to be recreated each time the typeface or font size changes.
 */
public final class TerminalRenderer {

    final int mTextSize;
    final TerminalFontFamily mFontFamily;
    final Typeface mTypeface;
    final boolean mForceFixedWidth;
    private final Paint mTextPaint = new Paint();

    /** The width of a single mono spaced character obtained by {@link Paint#measureText(String)} on a single 'X'. */
    final float mFontWidth;
    /** The {@link Paint#getFontSpacing()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    final int mFontLineSpacing;
    /** The {@link Paint#ascent()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    private final int mFontAscent;
    /** The {@link #mFontLineSpacing} + {@link #mFontAscent}. */
    final int mFontLineSpacingAndAscent;

    private final float[] asciiMeasures = new float[127];

    public TerminalRenderer(int textSize, Typeface typeface) {
        this(textSize, new TerminalFontFamily(typeface), true);
    }

    public TerminalRenderer(int textSize, TerminalFontFamily fontFamily) {
        this(textSize, fontFamily, true);
    }

    public TerminalRenderer(int textSize, TerminalFontFamily fontFamily, boolean forceFixedWidth) {
        mTextSize = textSize;
        mFontFamily = fontFamily == null ? new TerminalFontFamily(Typeface.MONOSPACE) : fontFamily;
        mTypeface = mFontFamily.getPrimaryTypeface();
        mForceFixedWidth = forceFixedWidth;

        mTextPaint.setTypeface(mTypeface);
        mTextPaint.setAntiAlias(true);
        mTextPaint.setTextSize(textSize);

        mFontLineSpacing = (int) Math.ceil(mTextPaint.getFontSpacing());
        mFontAscent = (int) Math.ceil(mTextPaint.ascent());
        mFontLineSpacingAndAscent = mFontLineSpacing + mFontAscent;
        mFontWidth = mTextPaint.measureText("X");

        StringBuilder sb = new StringBuilder(" ");
        for (int i = 0; i < asciiMeasures.length; i++) {
            sb.setCharAt(0, (char) i);
            asciiMeasures[i] = mTextPaint.measureText(sb, 0, 1);
        }
    }

    /** Render the terminal to a canvas with at a specified row scroll, and an optional rectangular selection. */
    public final void render(TerminalEmulator mEmulator, Canvas canvas, int topRow,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2) {
        final boolean reverseVideo = mEmulator.isReverseVideo();
        final int endRow = topRow + mEmulator.mRows;
        final int columns = mEmulator.mColumns;
        final int cursorCol = mEmulator.getCursorCol();
        final int cursorRow = mEmulator.getCursorRow();
        final boolean cursorVisible = mEmulator.shouldCursorBeVisible();
        final TerminalBuffer screen = mEmulator.getScreen();
        final int[] palette = mEmulator.mColors.mCurrentColors;
        final int cursorShape = mEmulator.getCursorStyle();

        if (reverseVideo)
            canvas.drawColor(palette[TextStyle.COLOR_INDEX_FOREGROUND], PorterDuff.Mode.SRC);

        float heightOffset = mFontLineSpacingAndAscent;
        for (int row = topRow; row < endRow; row++) {
            heightOffset += mFontLineSpacing;

            final int cursorX = (row == cursorRow && cursorVisible) ? cursorCol : -1;
            int selx1 = -1, selx2 = -1;
            if (row >= selectionY1 && row <= selectionY2) {
                if (row == selectionY1) selx1 = selectionX1;
                selx2 = (row == selectionY2) ? selectionX2 : mEmulator.mColumns;
            }

            TerminalRow lineObject = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
            final char[] line = lineObject.mText;
            final int charsUsedInLine = lineObject.getSpaceUsed();

            long lastRunStyle = 0;
            boolean lastRunInsideCursor = false;
            boolean lastRunInsideSelection = false;
            int lastRunStartColumn = -1;
            int lastRunStartIndex = 0;
            boolean lastRunFontWidthMismatch = false;
            Typeface lastRunTypeface = null;
            int currentCharIndex = 0;
            float measuredWidthForRun = 0.f;

            for (int column = 0; column < columns; ) {
                final char charAtIndex = line[currentCharIndex];
                final boolean charIsHighsurrogate = Character.isHighSurrogate(charAtIndex);
                int charsForCodePoint = charIsHighsurrogate ? 2 : 1;
                final int codePoint = charIsHighsurrogate ? Character.toCodePoint(charAtIndex, line[currentCharIndex + 1]) : charAtIndex;
                final int codePointWcWidth = WcWidth.width(codePoint);
                while (currentCharIndex + charsForCodePoint < charsUsedInLine &&
                    WcWidth.width(line, currentCharIndex + charsForCodePoint) <= 0) {
                    int combiningIndex = currentCharIndex + charsForCodePoint;
                    charsForCodePoint += Character.isHighSurrogate(line[combiningIndex]) ? 2 : 1;
                }
                final boolean insideCursor = (cursorX == column || (codePointWcWidth == 2 && cursorX == column + 1));
                final boolean insideSelection = column >= selx1 && column <= selx2;
                final long style = lineObject.getStyle(column);
                final int effect = TextStyle.decodeEffect(style);
                final boolean bold = (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
                final boolean italic = (effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0;
                final Typeface typeface = mFontFamily.getTypefaceForText(line, currentCharIndex, charsForCodePoint, bold, italic);
                mTextPaint.setTypeface(typeface);
                mTextPaint.setFakeBoldText(false);
                mTextPaint.setTextSkewX(0.f);

                // Check if the measured text width for this code point is not the same as that expected by wcwidth().
                // This could happen for some fonts which are not truly monospace, or for more exotic characters such as
                // smileys which android font renders as wide.
                // If this is detected, we draw this code point scaled to match what wcwidth() expects.
                final float measuredCodePointWidth = (!bold && !italic && typeface == mTypeface && charsForCodePoint == 1 && codePoint < asciiMeasures.length) ? asciiMeasures[codePoint] : mTextPaint.measureText(line,
                    currentCharIndex, charsForCodePoint);
                final boolean fontWidthMismatch = mForceFixedWidth &&
                    Math.abs(measuredCodePointWidth / mFontWidth - codePointWcWidth) > 0.01;

                if (style != lastRunStyle || insideCursor != lastRunInsideCursor ||
                    insideSelection != lastRunInsideSelection || typeface != lastRunTypeface ||
                    fontWidthMismatch || lastRunFontWidthMismatch) {
                    if (column == 0) {
                        // Skip first column as there is nothing to draw, just record the current style.
                    } else {
                        final int columnWidthSinceLastRun = column - lastRunStartColumn;
                        final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
                        int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
                        boolean invertCursorTextColor = false;
                        if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                            invertCursorTextColor = true;
                        }
                        drawTextRun(canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun,
                            lastRunStartIndex, charsSinceLastRun, measuredWidthForRun,
                            cursorColor, cursorShape, lastRunTypeface, lastRunStyle,
                            reverseVideo || invertCursorTextColor || lastRunInsideSelection);
                    }
                    measuredWidthForRun = 0.f;
                    lastRunStyle = style;
                    lastRunInsideCursor = insideCursor;
                    lastRunInsideSelection = insideSelection;
                    lastRunStartColumn = column;
                    lastRunStartIndex = currentCharIndex;
                    lastRunFontWidthMismatch = fontWidthMismatch;
                    lastRunTypeface = typeface;
                }
                measuredWidthForRun += measuredCodePointWidth;
                column += codePointWcWidth;
                currentCharIndex += charsForCodePoint;
            }

            final int columnWidthSinceLastRun = columns - lastRunStartColumn;
            final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
            int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
            boolean invertCursorTextColor = false;
            if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                invertCursorTextColor = true;
            }
            drawTextRun(canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun, lastRunStartIndex, charsSinceLastRun,
                measuredWidthForRun, cursorColor, cursorShape, lastRunTypeface, lastRunStyle,
                reverseVideo || invertCursorTextColor || lastRunInsideSelection);
        }
    }

    private void drawTextRun(Canvas canvas, char[] text, int[] palette, float y, int startColumn, int runWidthColumns,
                             int startCharIndex, int runWidthChars, float mes, int cursor, int cursorStyle,
                             Typeface typeface, long textStyle, boolean reverseVideo) {
        int foreColor = TextStyle.decodeForeColor(textStyle);
        final int effect = TextStyle.decodeEffect(textStyle);
        int backColor = TextStyle.decodeBackColor(textStyle);
        final boolean bold = (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
        final boolean underline = (effect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0;
        final boolean strikeThrough = (effect & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0;
        final boolean dim = (effect & TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0;

        if ((foreColor & 0xff000000) != 0xff000000) {
            // Let bold have bright colors if applicable (one of the first 8):
            if (bold && foreColor >= 0 && foreColor < 8) foreColor += 8;
            foreColor = palette[foreColor];
        }

        if ((backColor & 0xff000000) != 0xff000000) {
            backColor = palette[backColor];
        }

        // Reverse video here if _one and only one_ of the reverse flags are set:
        final boolean reverseVideoHere = reverseVideo ^ (effect & (TextStyle.CHARACTER_ATTRIBUTE_INVERSE)) != 0;
        if (reverseVideoHere) {
            int tmp = foreColor;
            foreColor = backColor;
            backColor = tmp;
        }

        float left = startColumn * mFontWidth;
        float right = left + runWidthColumns * mFontWidth;

        mes = mes / mFontWidth;
        boolean savedMatrix = false;
        if (mForceFixedWidth && mes > 0 && Math.abs(mes - runWidthColumns) > 0.01) {
            canvas.save();
            canvas.scale(runWidthColumns / mes, 1.f);
            left *= mes / runWidthColumns;
            right *= mes / runWidthColumns;
            savedMatrix = true;
        }

        if (backColor != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
            // Only draw non-default background.
            mTextPaint.setColor(backColor);
            canvas.drawRect(left, y - mFontLineSpacingAndAscent + mFontAscent, right, y, mTextPaint);
        }

        if (cursor != 0) {
            mTextPaint.setColor(cursor);
            float cursorHeight = mFontLineSpacingAndAscent - mFontAscent;
            if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) cursorHeight /= 4.;
            else if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) right -= ((right - left) * 3) / 4.;
            canvas.drawRect(left, y - cursorHeight, right, y, mTextPaint);
        }

        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) == 0) {
            if (dim) {
                int red = (0xFF & (foreColor >> 16));
                int green = (0xFF & (foreColor >> 8));
                int blue = (0xFF & foreColor);
                // Dim color handling used by libvte which in turn took it from xterm
                // (https://bug735245.bugzilla-attachments.gnome.org/attachment.cgi?id=284267):
                red = red * 2 / 3;
                green = green * 2 / 3;
                blue = blue * 2 / 3;
                foreColor = 0xFF000000 + (red << 16) + (green << 8) + blue;
            }

            mTextPaint.setTypeface(typeface == null ? mTypeface : typeface);
            mTextPaint.setFakeBoldText(false);
            mTextPaint.setUnderlineText(underline);
            mTextPaint.setTextSkewX(0.f);
            mTextPaint.setStrikeThruText(strikeThrough);
            mTextPaint.setColor(foreColor);

            // The text alignment is the default Paint.Align.LEFT.
            canvas.drawTextRun(text, startCharIndex, runWidthChars, startCharIndex, runWidthChars, left, y - mFontLineSpacingAndAscent, false, mTextPaint);
        }

        if (savedMatrix) canvas.restore();
    }

    /** Draw an underlined IME pre-edit buffer at the terminal cursor. */
    public void renderComposingText(TerminalEmulator emulator, Canvas canvas, int topRow,
                                    char[] text, int start, int len, int cursorOffsetInChars) {
        if (emulator == null || len <= 0) return;

        int cursorRow = emulator.getCursorRow();
        int cursorCol = emulator.getCursorCol();
        int visibleRow = cursorRow - topRow;
        if (visibleRow < 0 || visibleRow >= emulator.mRows) return;

        int[] palette = emulator.mColors.mCurrentColors;
        boolean reverseVideo = emulator.isReverseVideo();
        long style = emulator.getScreen().getStyleAt(cursorRow, cursorCol) |
            TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE;
        int effect = TextStyle.decodeEffect(style);
        boolean bold = (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
        boolean italic = (effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0;

        int runWidthColumns = columnsOccupied(text, start, len);
        if (runWidthColumns <= 0) runWidthColumns = 1;

        float heightOffset = mFontLineSpacingAndAscent + (visibleRow + 1) * mFontLineSpacing;
        float left = cursorCol * mFontWidth;

        // Erase the terminal cursor/cell beneath the preview, including the default background
        // which drawTextRun intentionally does not repaint.
        int backColor = TextStyle.decodeBackColor(style);
        if ((backColor & 0xff000000) != 0xff000000) backColor = palette[backColor];
        int foreColor = TextStyle.decodeForeColor(style);
        if ((foreColor & 0xff000000) != 0xff000000) {
            if ((effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0 &&
                foreColor >= 0 && foreColor < 8) foreColor += 8;
            foreColor = palette[foreColor];
        }
        boolean reverseVideoHere = reverseVideo ^
            ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVERSE) != 0);
        int visualBackground = reverseVideoHere ? foreColor : backColor;
        mTextPaint.setColor(visualBackground);
        mTextPaint.setStyle(Paint.Style.FILL);
        canvas.drawRect(left, heightOffset - mFontLineSpacing,
            left + runWidthColumns * mFontWidth, heightOffset, mTextPaint);

        int end = start + len;
        int columnOffset = 0;
        for (int charIndex = start; charIndex < end; ) {
            int clusterChars = glyphClusterLength(text, charIndex, end);
            int codePoint = Character.codePointAt(text, charIndex, end);
            int clusterColumns = Math.max(0, WcWidth.width(codePoint));
            if (clusterColumns == 0 && columnOffset == 0) clusterColumns = 1;
            Typeface typeface = mFontFamily.getTypefaceForText(text, charIndex, clusterChars, bold, italic);
            mTextPaint.setTypeface(typeface);
            mTextPaint.setFakeBoldText(false);
            mTextPaint.setTextSkewX(0.f);
            float measuredWidth = mTextPaint.measureText(text, charIndex, clusterChars);
            drawTextRun(canvas, text, palette, heightOffset, cursorCol + columnOffset, clusterColumns,
                charIndex, clusterChars, measuredWidth, 0, 0, typeface, style, reverseVideo);
            columnOffset += clusterColumns;
            charIndex += clusterChars;
        }

        // Show the IME's selection within its own composing span as a slim pre-edit cursor.
        if (cursorOffsetInChars >= 0 && cursorOffsetInChars <= len) {
            int columnsBeforeCursor = columnsOccupied(text, start, cursorOffsetInChars);
            float cursorX = left + columnsBeforeCursor * mFontWidth;
            float barWidth = Math.max(2.0f, mFontWidth * 0.12f);
            mTextPaint.setColor(foreColor);
            mTextPaint.setStyle(Paint.Style.FILL);
            canvas.drawRect(cursorX, heightOffset - mFontLineSpacing,
                cursorX + barWidth, heightOffset, mTextPaint);
        }
    }

    private int glyphClusterLength(char[] text, int start, int end) {
        int firstCodePoint = Character.codePointAt(text, start, end);
        int length = Character.charCount(firstCodePoint);
        while (start + length < end && WcWidth.width(text, start + length) <= 0) {
            int codePoint = Character.codePointAt(text, start + length, end);
            length += Character.charCount(codePoint);
        }
        return length;
    }

    private int columnsOccupied(char[] text, int start, int len) {
        int columns = 0;
        for (int offset = 0; offset < len; ) {
            char first = text[start + offset];
            int chars = Character.isHighSurrogate(first) && offset + 1 < len ? 2 : 1;
            int codePoint = chars == 2 ? Character.toCodePoint(first, text[start + offset + 1]) : first;
            columns += Math.max(0, WcWidth.width(codePoint));
            offset += chars;
        }
        return columns;
    }

    public float getFontWidth() {
        return mFontWidth;
    }

    public int getFontLineSpacing() {
        return mFontLineSpacing;
    }
}
