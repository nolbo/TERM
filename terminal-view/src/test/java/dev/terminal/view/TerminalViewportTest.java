package dev.terminal.view;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class TerminalViewportTest {
    @Test public void bottomAlignmentKeepsAllRowsAndRemovesSlackAcrossToolbarHeights() {
        for (int height : new int[]{803, 1758, 2000}) {
            for (int safeInset : new int[]{0, 63}) {
                int rows = (height - safeInset - 7) / 31;
                int contentInset = TerminalViewport.bottomAlignedTopInset(height, safeInset, 7, 31);
                assertEquals(height, contentInset + 7 + rows * 31);
                assertEquals(rows, (height - contentInset - 7) / 31);
            }
        }
    }

    @Test public void smallViewportsKeepSafeInsetWhenMinimumRowsDoNotFit() {
        assertEquals(63, TerminalViewport.bottomAlignedTopInset(100, 63, 7, 31));
    }

    @Test public void oldestLineStartsBelowInset() {
        assertEquals(-100, TerminalViewport.firstDrawnRow(-100, 100, 63, 40));
    }

    @Test public void scrollingDrawsEarlierLinesBehindChrome() {
        assertEquals(-100, TerminalViewport.firstDrawnRow(-99, 100, 63, 40));
        assertEquals(-92, TerminalViewport.firstDrawnRow(-90, 100, 63, 40));
        assertEquals(-2, TerminalViewport.firstDrawnRow(0, 100, 63, 40));
    }

    @Test public void noInsetOrNoHistoryDoesNotAddRows() {
        assertEquals(-90, TerminalViewport.firstDrawnRow(-90, 100, 0, 40));
        assertEquals(0, TerminalViewport.firstDrawnRow(0, 0, 63, 40));
    }
}
