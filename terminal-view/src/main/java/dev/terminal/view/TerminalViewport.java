package dev.terminal.view;

/** Geometry shared by text and graphics when scrollback extends behind top system chrome. */
final class TerminalViewport {
    private TerminalViewport() {}

    /** Put fractional-row slack above the grid, keeping its last complete row at the bottom. */
    static int bottomAlignedTopInset(int viewHeight, int topInset, int lineOffset, int lineSpacing) {
        int available = viewHeight - topInset - lineOffset;
        if (lineSpacing <= 0 || available / lineSpacing < 4) return topInset;
        return topInset + available % lineSpacing;
    }

    static int firstDrawnRow(int topRow, int transcriptRows, int topInset, int lineSpacing) {
        int precedingRows = (int) Math.ceil((double) topInset / lineSpacing);
        return Math.max(-transcriptRows, topRow - precedingRows);
    }
}
