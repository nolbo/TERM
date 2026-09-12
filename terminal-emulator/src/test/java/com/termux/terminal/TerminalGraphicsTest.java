package com.termux.terminal;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Arrays;
import java.util.zip.Deflater;

public class TerminalGraphicsTest extends TerminalTestCase {
    private static String kitty(String header, String payload) { return "\033_G" + header + ";" + payload + "\033\\"; }
    private TerminalGraphics.Placement first() { return mTerminal.getGraphics().getPlacements().get(0); }

    public void testSixelPaletteRepeatTransparencyAndCursor() {
        withTerminalSized(20, 8);
        enterString("\033P0;1q\"1;1;3;6#1;2;100;0;0#1!3@\033\\");
        assertEquals(3, first().image.width);
        assertEquals(6, first().image.height);
        assertEquals(0xffff0000, first().image.pixels[0]);
        assertEquals(0, first().image.pixels[3]);
        assertEquals(1, mTerminal.getCursorRow());
        assertEquals(0, mTerminal.getCursorCol());
        assertEquals(3f / INITIAL_CELL_WIDTH_PIXELS, first().widthCells);
    }

    public void testSixelTrueColorBackgroundAndDisplayMode() {
        withTerminalSized(20, 8);
        enterString("\033[48;2;1;2;3m\033[?80h\033[4;5H\033Pq?\033\\");
        assertEquals(0xff010203, first().image.pixels[0]);
        assertEquals(0, first().row);
        assertEquals(0, first().column);
        assertEquals(3, mTerminal.getCursorRow());
        assertEquals(4, mTerminal.getCursorCol());
    }

    public void testKittyRawQueryDisplayDeleteAndUnsignedIds() {
        withTerminalSized(20, 8);
        assertEnteringStringGivesResponse(kitty("a=q,i=4294967295,f=24,s=1,v=1", "/wAA"),
            kitty("i=4294967295", "OK"));
        assertTrue(mTerminal.getGraphics().getPlacements().isEmpty());
        enterString(kitty("a=T,i=42,p=7,f=32,s=1,v=1,c=2,r=3,C=1", "AP8AgA=="));
        assertEquals(0x8000ff00, first().image.pixels[0]);
        assertEquals(2, first().columns);
        assertEquals(3, first().rows);
        assertEquals(0, mTerminal.getCursorRow());
        assertEquals(kitty("i=42,p=7", "OK"), mOutput.getOutputAndClear());
        enterString(kitty("a=d,d=I,i=42,p=7", ""));
        assertTrue(mTerminal.getGraphics().getPlacements().isEmpty());
        assertEnteringStringGivesResponse(kitty("a=p,i=42", ""), kitty("i=42", "ENOENT: image"));
    }

    public void testChunkedTransmissionAndEveryByteSplit() {
        withTerminalSized(20, 8);
        String input = kitty("a=T,i=4,f=24,s=2,v=1,m=1", "/wAA") + kitty("m=0", "AP8A");
        for (byte b : input.getBytes(StandardCharsets.UTF_8)) mTerminal.append(new byte[]{b}, 1);
        assertEquals(2, first().image.width);
        assertEquals(0xffff0000, first().image.pixels[0]);
        assertEquals(0xff00ff00, first().image.pixels[1]);
        assertEquals(kitty("i=4", "OK"), mOutput.getOutputAndClear());
    }

    public void testUnsupportedMediumDoesNotReadGuestPath() {
        withTerminalSized(20, 8);
        assertEnteringStringGivesResponse(kitty("a=T,i=1,t=f,f=100", "L2V0Yy9wYXNzd2Q="),
            kitty("i=1", "ENOTSUP: use direct transmission"));
        assertTrue(mTerminal.getGraphics().getPlacements().isEmpty());
        assertEnteringStringGivesResponse(kitty("a=q,i=2,f=24,s=999999,v=1", "AAAA"),
            kitty("i=2", "E2BIG: dimensions"));
    }

    public void testMalformedChunkCancelsTransferAndParserRecovers() {
        withTerminalSized(20, 8);
        enterString(kitty("a=T,i=4,f=24,s=2,v=1,m=1", "/wAA"));
        enterString(kitty("m=0", "!!!!"));
        assertTrue(mTerminal.getGraphics().getPlacements().isEmpty());
        assertTrue(mOutput.getOutputAndClear().contains("EINVAL"));
        enterString(kitty("a=T,i=5,f=24,s=1,v=1", "AP8A") + "OK");
        assertEquals(5L, first().imageId);
        assertTrue(mTerminal.getScreen().getTranscriptText().contains("OK"));
    }

    public void testOversizedStringsAreDrainedAndCancelled() {
        withTerminalSized(20, 8);
        char[] huge = new char[TerminalGraphics.MAX_TRANSFER_BYTES + 10];
        Arrays.fill(huge, '?');
        enterString("\033Pq" + new String(huge) + "\033\\SAFE");
        assertTrue(mTerminal.getGraphics().getPlacements().isEmpty());
        assertEquals("SAFE", mTerminal.getScreen().getTranscriptText());
        enterString("\033_Ga=T;AAAA\030TEXT");
        assertEquals("SAFETEXT", mTerminal.getScreen().getTranscriptText());
    }

    public void testSixelExpansionBombRejectedWithoutPartialImage() {
        withTerminalSized(20, 8);
        enterString("\033P" + new String(new char[1000]).replace('\0', ';') + "q~\033\\");
        enterString("\033Pq!65535~\033\\OK");
        assertTrue(mTerminal.getGraphics().getPlacements().isEmpty());
        assertEquals("OK", mTerminal.getScreen().getTranscriptText());
    }

    public void testCompression() {
        withTerminalSized(20, 8);
        Deflater deflater = new Deflater();
        deflater.setInput(new byte[]{(byte) 255, 0, 0});
        deflater.finish();
        byte[] buffer = new byte[100];
        int length = deflater.deflate(buffer);
        deflater.end();
        enterString(kitty("a=T,i=1,f=24,s=1,v=1,o=z", Base64.getEncoder().encodeToString(Arrays.copyOf(buffer, length))));
        assertEquals(0xffff0000, first().image.pixels[0]);
    }

    public void testScrollEraseAlternateScreenAndResize() {
        withTerminalSized(20, 8);
        enterString(kitty("a=T,i=1,f=24,s=1,v=1,C=1", "/wAA"));
        enterString("\033[8;1H\n");
        assertEquals(-1, first().row);
        enterString("\033[?1049h");
        assertTrue(mTerminal.getGraphics().getPlacements().isEmpty());
        enterString("\033Pq~\033\\");
        enterString("\033[?1049l");
        assertEquals(1L, first().imageId);
        mTerminal.resize(24, 8, 10, 20);
        assertEquals(1L, first().imageId);
        enterString("\033[3J");
        assertTrue(mTerminal.getGraphics().getPlacements().isEmpty());
        enterString(kitty("a=T,i=2,f=24,s=1,v=1,C=1", "/wAA"));
        enterString("\033[2J");
        assertTrue(mTerminal.getGraphics().getPlacements().isEmpty());
    }

    public void testWideImageScrollAndPartialRegionKeepExistingHistory() {
        withTerminalSized(20, 8);
        enterString(kitty("a=T,i=1,f=24,s=1,v=1,c=30,r=1,C=1", "/wAA"));
        enterString("\033[8;1H\n");
        assertEquals(-1, first().row);
        enterString("\033[4;1H\033[L");
        assertEquals(-1, first().row);
    }

    public void testReplayReconstructsImagesWithoutProtocolResponses() {
        withTerminalSized(20, 8);
        byte[] replay = (kitty("a=T,i=1,f=24,s=1,v=1", "/wAA") + "\033Pq~\033\\").getBytes(StandardCharsets.UTF_8);
        mTerminal.appendReplay(replay, replay.length);
        assertEquals(2, mTerminal.getGraphics().getPlacements().size());
        assertEquals("", mOutput.getOutputAndClear());
        enterString(kitty("a=q,i=2,f=24,s=1,v=1", "/wAA"));
        assertEquals(kitty("i=2", "OK"), mOutput.getOutputAndClear());
    }

    public void testKittyCursorAndSameZOrdering() {
        withTerminalSized(20, 8);
        enterString(kitty("a=T,i=9,f=24,s=1,v=1,c=2,r=3", "/wAA"));
        assertEquals(3, mTerminal.getCursorRow());
        assertEquals(2, mTerminal.getCursorCol());
        enterString(kitty("a=T,i=1,f=24,s=1,v=1,C=1", "AP8A"));
        assertEquals(1L, first().imageId);
    }

    public void testQuietResponsesAndImageQuota() {
        withTerminalSized(20, 8);
        for (int i = 1; i <= 70; i++) enterString(kitty("a=T,q=2,C=1,i=" + i + ",f=24,s=1,v=1", "/wAA"));
        assertEquals(64, mTerminal.getGraphics().getPlacements().size());
        assertEquals("", mOutput.getOutputAndClear());
        assertEnteringStringGivesResponse(kitty("a=p,i=1", ""), kitty("i=1", "ENOENT: image"));
        mTerminal.reset();
        assertTrue(mTerminal.getGraphics().getPlacements().isEmpty());
    }
}
