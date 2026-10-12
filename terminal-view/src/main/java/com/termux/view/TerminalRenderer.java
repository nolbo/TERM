package com.termux.view;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Picture;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalGraphics;
import com.termux.terminal.KittyPlaceholders;
import com.termux.terminal.TerminalImage;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.TextStyle;
import com.termux.terminal.WcWidth;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;

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
    final TerminalGlyphRenderOptions mGlyphRenderOptions;
    private final Paint mTextPaint = new Paint();
    private int mDrawPass; // 0: ordinary text, 1: backgrounds only, 2: glyphs/cursor only
    // Cache only visible images, within the same pixel budget as one emulator screen.
    private final Map<TerminalImage, Bitmap> mImageBitmaps = new LinkedHashMap<>();

    /** The width of a single mono spaced character obtained by {@link Paint#measureText(String)} on a single 'X'. */
    final float mFontWidth;
    /** The {@link Paint#getFontSpacing()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    final int mFontLineSpacing;
    /** The {@link Paint#ascent()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    private final int mFontAscent;
    /** The {@link #mFontLineSpacing} + {@link #mFontAscent}. */
    final int mFontLineSpacingAndAscent;

    // One presented display list, not a copy of scrollback or another PTY owner.
    private Picture mPresentedFrame;
    private TerminalEmulator mPresentedEmulator;
    private int mPresentedTopRow, mPresentedColumns, mPresentedRows;
    private float mPresentedScrollOffset;
    public void clearPresentedFrame() { mPresentedFrame = null; mPresentedEmulator = null; mImageBitmaps.clear(); }

    private final float[] asciiMeasures = new float[127];

    public TerminalRenderer(int textSize, Typeface typeface) {
        this(textSize, new TerminalFontFamily(typeface), true);
    }

    public TerminalRenderer(int textSize, TerminalFontFamily fontFamily) {
        this(textSize, fontFamily, true);
    }

    public TerminalRenderer(int textSize, TerminalFontFamily fontFamily, boolean forceFixedWidth) {
        this(textSize, fontFamily, forceFixedWidth, TerminalGlyphRenderOptions.DEFAULT);
    }

    public TerminalRenderer(int textSize, TerminalFontFamily fontFamily, boolean forceFixedWidth,
                            TerminalGlyphRenderOptions glyphRenderOptions) {
        mTextSize = textSize;
        mFontFamily = fontFamily == null ? new TerminalFontFamily(Typeface.MONOSPACE) : fontFamily;
        mTypeface = mFontFamily.getPrimaryTypeface();
        mForceFixedWidth = forceFixedWidth;
        mGlyphRenderOptions = glyphRenderOptions == null ? TerminalGlyphRenderOptions.DEFAULT : glyphRenderOptions;

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

    /** Render graphics in the protocol's three layers around text backgrounds and glyphs. */
    public final void render(TerminalEmulator emulator, Canvas canvas, int topRow,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2) {
        render(emulator, canvas, topRow, selectionY1, selectionY2, selectionX1, selectionX2, 0);
    }

    public final void render(TerminalEmulator emulator, Canvas canvas, int topRow,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2,
                             float scrollOffset) {
        boolean sameViewport = mPresentedEmulator == emulator && mPresentedFrame != null &&
            mPresentedFrame.getWidth() == canvas.getWidth() && mPresentedFrame.getHeight() == canvas.getHeight() &&
            mPresentedColumns == emulator.mColumns && mPresentedRows == emulator.mRows && mPresentedTopRow == topRow
            && mPresentedScrollOffset == scrollOffset;
        // A user scroll/viewport change must remain visible during output batching.
        // Freeze only the viewport the presented frame actually represents.
        if (emulator.getSynchronizedOutputDelayMillis() > 0 &&
                (sameViewport || mPresentedFrame == null || mPresentedEmulator != emulator)) {
            if (sameViewport) canvas.drawPicture(mPresentedFrame);
            return;
        }
        // Record a complete terminal frame so cursor/IME/system invalidations cannot
        // expose a partially received synchronized update between PTY packets.
        Picture frame = new Picture();
        Canvas recording = frame.beginRecording(Math.max(1, canvas.getWidth()), Math.max(1, canvas.getHeight()));
        int visibleRows = emulator.mRows + (scrollOffset > 0 && topRow < 0 ? 1 : 0);
        recording.translate(0, -scrollOffset * mFontLineSpacing);
        try { renderFrame(emulator, recording, topRow, selectionY1, selectionY2, selectionX1, selectionX2, visibleRows); }
        finally { frame.endRecording(); }
        mPresentedFrame = frame;
        mPresentedEmulator = emulator;
        mPresentedColumns = emulator.mColumns; mPresentedRows = emulator.mRows; mPresentedTopRow = topRow;
        mPresentedScrollOffset = scrollOffset;
        canvas.drawPicture(frame);
    }

    private void renderFrame(TerminalEmulator emulator, Canvas canvas, int topRow,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2, int visibleRows) {
        if (emulator.isReverseVideo())
            canvas.drawColor(emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND], PorterDuff.Mode.SRC);
        List<TerminalGraphics.Placement> images = emulator.getGraphics().getRenderPlacements(
            emulator.getScreen(), topRow, topRow + visibleRows, emulator.mColumns, System.nanoTime() / 1_000_000);
        Set<TerminalImage> visible = new HashSet<>();
        for (TerminalGraphics.Placement p : images)
            if (p.row + p.clipBottom > topRow && p.row + p.clipTop < topRow + visibleRows) visible.add(p.image);
        mImageBitmaps.keySet().retainAll(visible);
        try {
            drawImages(canvas, images, emulator, topRow, Integer.MIN_VALUE, -1073741825, visibleRows);
            mDrawPass = 1;
            renderText(emulator, canvas, topRow, selectionY1, selectionY2, selectionX1, selectionX2, visibleRows);
            drawImages(canvas, images, emulator, topRow, -1073741824, -1, visibleRows);
            mDrawPass = 2;
            renderText(emulator, canvas, topRow, selectionY1, selectionY2, selectionX1, selectionX2, visibleRows);
            drawImages(canvas, images, emulator, topRow, 0, Integer.MAX_VALUE, visibleRows);
        } finally { mDrawPass = 0; }
    }

    private void renderText(TerminalEmulator mEmulator, Canvas canvas, int topRow,
                            int selectionY1, int selectionY2, int selectionX1, int selectionX2, int visibleRows) {
        final boolean reverseVideo = mEmulator.isReverseVideo();
        final int endRow = Math.min(mEmulator.mRows, topRow + visibleRows);
        final int columns = mEmulator.mColumns;
        final int cursorCol = mEmulator.getCursorCol();
        final int cursorRow = mEmulator.getCursorRow();
        final boolean cursorVisible = mEmulator.shouldCursorBeVisible();
        final TerminalBuffer screen = mEmulator.getScreen();
        final int[] palette = mEmulator.mColors.mCurrentColors;
        final int cursorShape = mEmulator.getCursorStyle();

        float heightOffset = mFontLineSpacingAndAscent;
        for (int row = topRow; row < endRow; row++) {
            heightOffset += mFontLineSpacing;

            final int cursorX = (row == cursorRow && cursorVisible) ? cursorCol : -1;
            int selx1 = -1, selx2 = -1;
            if (row >= selectionY1 && row <= selectionY2) {
                selx1 = (row == selectionY1) ? selectionX1 : 0;
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
            boolean lastRunNaturalWideGlyph = false;
            boolean lastRunPlaceholder = false;
            Typeface lastRunTypeface = null;
            int currentCharIndex = 0;
            float measuredWidthForRun = 0.f;

            for (int column = 0; column < columns; ) {
                final char charAtIndex = line[currentCharIndex];
                final boolean charIsHighsurrogate = Character.isHighSurrogate(charAtIndex);
                int charsForCodePoint = charIsHighsurrogate ? 2 : 1;
                final int codePoint = charIsHighsurrogate ? Character.toCodePoint(charAtIndex, line[currentCharIndex + 1]) : charAtIndex;
                final int codePointWcWidth = WcWidth.width(codePoint);
                final boolean placeholder = codePoint == KittyPlaceholders.CODE_POINT;
                while (currentCharIndex + charsForCodePoint < charsUsedInLine &&
                    WcWidth.width(line, currentCharIndex + charsForCodePoint) <= 0) {
                    int combiningIndex = currentCharIndex + charsForCodePoint;
                    charsForCodePoint += Character.isHighSurrogate(line[combiningIndex]) ? 2 : 1;
                }
                final boolean insideCursor = (cursorX == column || (codePointWcWidth == 2 && cursorX == column + 1));
                final boolean insideSelection = TerminalGlyphPlacement.intersectsSelection(
                    column, codePointWcWidth, selx1, selx2);
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
                final boolean naturalWideGlyph = mGlyphRenderOptions.naturalWideGlyphs() &&
                    codePointWcWidth == 2;
                final boolean fontWidthMismatch = !naturalWideGlyph && mForceFixedWidth &&
                    Math.abs(measuredCodePointWidth / mFontWidth - codePointWcWidth) > 0.01;

                if (style != lastRunStyle || insideCursor != lastRunInsideCursor ||
                    insideSelection != lastRunInsideSelection || typeface != lastRunTypeface ||
                    fontWidthMismatch || lastRunFontWidthMismatch ||
                    naturalWideGlyph || lastRunNaturalWideGlyph || placeholder || lastRunPlaceholder) {
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
                            reverseVideo || invertCursorTextColor || lastRunInsideSelection,
                            mForceFixedWidth || lastRunFontWidthMismatch, lastRunNaturalWideGlyph);
                    }
                    measuredWidthForRun = 0.f;
                    lastRunStyle = style;
                    lastRunInsideCursor = insideCursor;
                    lastRunInsideSelection = insideSelection;
                    lastRunStartColumn = column;
                    lastRunStartIndex = currentCharIndex;
                    lastRunFontWidthMismatch = fontWidthMismatch;
                    lastRunNaturalWideGlyph = naturalWideGlyph;
                    lastRunPlaceholder = placeholder;
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
                reverseVideo || invertCursorTextColor || lastRunInsideSelection,
                mForceFixedWidth || lastRunFontWidthMismatch, lastRunNaturalWideGlyph);
        }
    }

    private void drawImages(Canvas canvas, List<TerminalGraphics.Placement> images, TerminalEmulator emulator,
                            int topRow, int minimumZ, int maximumZ, int visibleRows) {
        int save = canvas.save();
        canvas.clipRect(0, mFontLineSpacingAndAscent, emulator.mColumns * mFontWidth,
            mFontLineSpacingAndAscent + visibleRows * mFontLineSpacing);
        for (TerminalGraphics.Placement p : images) {
            if (p.z < minimumZ || p.z > maximumZ || p.row + p.clipBottom <= topRow ||
                p.row + p.clipTop >= topRow + visibleRows || p.sourceWidth <= 0 || p.sourceHeight <= 0) continue;
            int imageSave = canvas.save();
            float left = p.column * mFontWidth;
            float top = mFontLineSpacingAndAscent + (p.row - topRow) * mFontLineSpacing;
            canvas.clipRect(left + p.clipLeft * mFontWidth, top + p.clipTop * mFontLineSpacing,
                left + p.clipRight * mFontWidth, top + p.clipBottom * mFontLineSpacing);
            float width = p.widthCells * mFontWidth, height = p.heightCells * mFontLineSpacing;
            float scale = Math.min(width / p.sourceWidth, height / p.sourceHeight);
            float drawnWidth = p.sourceWidth * scale, drawnHeight = p.sourceHeight * scale;
            left += p.offsetXCells * mFontWidth + (width - drawnWidth) / 2;
            top += p.offsetYCells * mFontLineSpacing + (height - drawnHeight) / 2;
            Bitmap bitmap = mImageBitmaps.get(p.image);
            if (bitmap == null) {
                bitmap = Bitmap.createBitmap(p.image.pixels, p.image.width, p.image.height, Bitmap.Config.ARGB_8888);
                mImageBitmaps.put(p.image, bitmap);
            }
            canvas.drawBitmap(bitmap, new Rect(p.sourceX, p.sourceY, p.sourceX + p.sourceWidth, p.sourceY + p.sourceHeight),
                new RectF(left, top, left + drawnWidth, top + drawnHeight), null);
            canvas.restoreToCount(imageSave);
        }
        canvas.restoreToCount(save);
    }

    private void drawTextRun(Canvas canvas, char[] text, int[] palette, float y, int startColumn, int runWidthColumns,
                             int startCharIndex, int runWidthChars, float mes, int cursor, int cursorStyle,
                             Typeface typeface, long textStyle, boolean reverseVideo, boolean forceCellWidth,
                             boolean naturalWideGlyph) {
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

        if (mDrawPass != 2 && (backColor != palette[TextStyle.COLOR_INDEX_BACKGROUND] ||
            TextStyle.decodeBackColor(textStyle) != TextStyle.COLOR_INDEX_BACKGROUND || reverseVideoHere)) {
            // Only draw non-default background.
            mTextPaint.setColor(backColor);
            canvas.drawRect(left, y - mFontLineSpacingAndAscent + mFontAscent, right, y, mTextPaint);
        }

        if (mDrawPass == 1) return;

        if (cursor != 0) {
            mTextPaint.setColor(cursor);
            float cursorHeight = mFontLineSpacingAndAscent - mFontAscent;
            float cursorRight = right;
            if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) cursorHeight /= 4.;
            else if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR)
                cursorRight -= ((cursorRight - left) * 3) / 4.;
            canvas.drawRect(left, y - cursorHeight, cursorRight, y, mTextPaint);
        }

        if (runWidthChars > 0 && Character.codePointAt(text, startCharIndex, startCharIndex + runWidthChars) == KittyPlaceholders.CODE_POINT)
            return; // Placeholder colors/combining marks are metadata, never visible glyphs.

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
            mTextPaint.setTextSkewX(0.f);
            mTextPaint.setColor(foreColor);

            final float primaryBaseline = y - mFontLineSpacingAndAscent;
            if (naturalWideGlyph) {
                // Keep the cell geometry above untouched. Only the shaped glyph is transformed.
                // Text bounds, rather than advance, account for left/right bearings when centering.
                mTextPaint.setUnderlineText(false);
                mTextPaint.setStrikeThruText(false);
                Rect visualBounds = new Rect();
                mTextPaint.getTextBounds(text, startCharIndex, runWidthChars, visualBounds);
                TerminalGlyphPlacement.Result placement = TerminalGlyphPlacement.place(
                    left, right - left, y - mFontLineSpacing, y, primaryBaseline,
                    visualBounds.left, visualBounds.top, visualBounds.right, visualBounds.bottom, mes,
                    mGlyphRenderOptions.fallbackScale() * mFontFamily.getScale(typeface),
                    (mGlyphRenderOptions.fallbackBaselineOffsetEm() +
                        mFontFamily.getBaselineOffsetEm(typeface)) * mTextSize);

                canvas.save();
                canvas.translate(placement.originX, placement.baselineY);
                canvas.scale(placement.scale, placement.scale);
                canvas.drawTextRun(text, startCharIndex, runWidthChars, startCharIndex, runWidthChars,
                    0.0f, 0.0f, false, mTextPaint);
                canvas.restore();

                if (underline) drawLogicalDecoration(canvas, left, right,
                    placement.baselineY + placement.scale * mTextPaint.getUnderlinePosition(),
                    placement.scale * mTextPaint.getUnderlineThickness());
                if (strikeThrough) drawLogicalDecoration(canvas, left, right,
                    placement.baselineY + placement.scale * mTextPaint.getStrikeThruPosition(),
                    placement.scale * mTextPaint.getStrikeThruThickness());
            } else {
                mTextPaint.setUnderlineText(underline);
                mTextPaint.setStrikeThruText(strikeThrough);
                float measuredCellWidth = mes / mFontWidth;
                if (forceCellWidth && measuredCellWidth > 0.0f &&
                    Math.abs(measuredCellWidth - runWidthColumns) > 0.01) {
                    canvas.save();
                    canvas.scale(runWidthColumns / measuredCellWidth, 1.0f);
                    float scaledLeft = left * measuredCellWidth / runWidthColumns;
                    canvas.drawTextRun(text, startCharIndex, runWidthChars, startCharIndex, runWidthChars,
                        scaledLeft, primaryBaseline, false, mTextPaint);
                    canvas.restore();
                } else {
                    // The text alignment is the default Paint.Align.LEFT.
                    canvas.drawTextRun(text, startCharIndex, runWidthChars, startCharIndex, runWidthChars,
                        left, primaryBaseline, false, mTextPaint);
                }
            }
        }
    }

    private void drawLogicalDecoration(Canvas canvas, float left, float right, float centerY,
                                       float requestedThickness) {
        float thickness = Math.max(1.0f, requestedThickness);
        canvas.drawRect(left, centerY - thickness * 0.5f, right, centerY + thickness * 0.5f, mTextPaint);
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
            boolean naturalWideGlyph = mGlyphRenderOptions.naturalWideGlyphs() && clusterColumns == 2;
            drawTextRun(canvas, text, palette, heightOffset, cursorCol + columnOffset, clusterColumns,
                charIndex, clusterChars, measuredWidth, 0, 0, typeface, style, reverseVideo,
                !naturalWideGlyph && mForceFixedWidth, naturalWideGlyph);
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
