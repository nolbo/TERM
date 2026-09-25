package com.termux.view;

/** Geometry shared by text and graphics when scrollback extends behind top system chrome. */
final class TerminalViewport {
    private TerminalViewport() {}

    static int firstDrawnRow(int topRow, int transcriptRows, int topInset, int lineSpacing) {
        int precedingRows = (int) Math.ceil((double) topInset / lineSpacing);
        return Math.max(-transcriptRows, topRow - precedingRows);
    }
}
