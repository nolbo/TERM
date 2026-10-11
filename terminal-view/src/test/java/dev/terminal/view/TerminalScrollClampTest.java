package dev.terminal.view;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TerminalScrollClampTest {
    @Test
    public void clampsLargeFlingStepsWithoutWalkingEachRow() {
        assertEquals(-100, TerminalView.clampScrollRow(-20, -500, 100));
        assertEquals(0, TerminalView.clampScrollRow(-80, 500, 100));
        assertEquals(-42, TerminalView.clampScrollRow(-40, -2, 100));
    }
}
