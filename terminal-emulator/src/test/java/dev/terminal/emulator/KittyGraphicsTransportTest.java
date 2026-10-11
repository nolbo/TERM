package dev.terminal.emulator;

import junit.framework.TestCase;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Arrays;

public class KittyGraphicsTransportTest extends TestCase {
    private static byte[] sequence(String header, String payload) {
        return ("\033_G" + header + ";" + payload + "\033\\").getBytes(StandardCharsets.UTF_8);
    }
    private static String path(String name) { return Base64.getEncoder().encodeToString(name.getBytes(StandardCharsets.UTF_8)); }
    private static byte[] flatten(KittyGraphicsTransport.Output output) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (byte[] frame : output.bytes) { assertTrue(frame.length <= 32 * 1024); bytes.write(frame, 0, frame.length); }
        return bytes.toByteArray();
    }

    public void testEveryFragmentBoundaryAndMediaOffset() {
        byte[] input = sequence("a=T,i=1,t=f,f=24,s=1,v=1,O=7,S=3", path("/tmp/이미지"));
        for (int split = 0; split <= input.length; split++) {
            int[] calls = {0};
            KittyGraphicsTransport transport = new KittyGraphicsTransport((medium, name, offset, size) -> {
                calls[0]++; assertEquals('f', medium); assertEquals("/tmp/이미지", name);
                assertEquals(7L, offset); assertEquals(3L, size); return new byte[]{(byte) 255, 0, 0};
            });
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] first = flatten(transport.accept(Arrays.copyOfRange(input, 0, split)));
            byte[] second = flatten(transport.accept(Arrays.copyOfRange(input, split, input.length)));
            out.write(first, 0, first.length); out.write(second, 0, second.length);
            String result = new String(out.toByteArray(), StandardCharsets.UTF_8);
            assertEquals(1, calls[0]); assertTrue(result.contains("t=d")); assertTrue(result.endsWith(";/wAA\033\\"));
            assertFalse(result.contains("O=7")); assertFalse(result.contains("S=3"));
        }
    }

    public void testTransformedMediaUsesNormalParserAndReplayWithoutRereading() {
        KittyGraphicsTransport transport = new KittyGraphicsTransport((medium, name, offset, size) -> new byte[]{(byte) 255, 0, 0});
        byte[] output = flatten(transport.accept(sequence("a=T,i=9,t=t,f=24,s=1,v=1", path("/tmp/tty-graphics-protocol-image"))));
        TerminalGraphics graphics = new TerminalGraphics();
        String wire = new String(output, StandardCharsets.US_ASCII);
        TerminalGraphics.Result result = graphics.kitty(wire.substring(3, wire.length() - 2), 0, 0, 10, 20);
        assertEquals(0xffff0000, graphics.getPlacements().get(0).image.pixels[0]);
        assertTrue(result.response.contains("OK"));
        assertFalse(wire.contains("tty-graphics"));
    }

    public void testOtherStringsUnicodeAndDirectDataAreByteIdentical() {
        byte[] command = sequence("a=T,i=1,t=f", path("/private/path"));
        for (String input : new String[]{
            "\033]0;" + new String(command, StandardCharsets.UTF_8) + "\007",
            "\033Pq" + new String(command, StandardCharsets.UTF_8) + "\033\\",
            "한글" + new String(Character.toChars(0x10eeee)) + "\u009f",
            new String(sequence("a=T,i=1,f=24,s=1,v=1", "/wAA"), StandardCharsets.UTF_8),
        }) {
            KittyGraphicsTransport transport = new KittyGraphicsTransport((m, n, o, s) -> { fail("must not resolve"); return null; });
            byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
            assertTrue(Arrays.equals(bytes, flatten(transport.accept(bytes))));
        }
    }

    public void testErrorsReturnOnlyBoundedProtocolReplyAndHonorQuiet() {
        for (int q = 0; q <= 2; q++) {
            KittyGraphicsTransport transport = new KittyGraphicsTransport((m, n, o, s) -> { throw new IOException("ENOENT: media file"); });
            KittyGraphicsTransport.Output output = transport.accept(sequence("a=q,i=7,t=s,q=" + q, path("/missing")));
            assertTrue(output.bytes.isEmpty());
            if (q == 2) assertTrue(output.replies.isEmpty());
            else assertTrue(Arrays.equals(sequence("i=7", "ENOENT: media file"), output.replies.get(0)));
        }
    }

    public void testLargeFileFramesHaveValidAnimationContinuationHeaders() {
        byte[] pixels = new byte[50000]; Arrays.fill(pixels, (byte) 3);
        KittyGraphicsTransport transport = new KittyGraphicsTransport((m, n, o, s) -> pixels);
        String output = new String(flatten(transport.accept(sequence("a=f,i=1,t=f", path("/tmp/frame")))), StandardCharsets.US_ASCII);
        assertTrue(output.contains("\033_Ga=f,m=1;")); assertTrue(output.contains("\033_Ga=f,m=0;"));
        assertFalse(output.contains("t=f"));
    }

    public void testInvalidCancelledOversizedAndInterruptedTransfersDoNotRead() {
        KittyGraphicsTransport transport = new KittyGraphicsTransport((m, n, o, s) -> { fail("must not resolve"); return null; });
        for (String header : new String[]{"a=T,i=1,I=2,t=f", "a=T,i=1,t=f,m=1", "a=T,i=1,t=f,q=9"}) {
            byte[] input = sequence(header, path("/tmp/file"));
            assertTrue(Arrays.equals(input, flatten(transport.accept(input))));
        }
        byte[] oversized = sequence("a=T,t=f", new String(new char[6000]).replace('\0', 'A'));
        assertTrue(Arrays.equals(oversized, flatten(transport.accept(oversized))));
        byte[] cancelled = "\033_Ga=T,t=f;L3RtcA==\030safe".getBytes(StandardCharsets.US_ASCII);
        assertTrue(Arrays.equals(cancelled, flatten(transport.accept(cancelled))));
        transport.accept(sequence("a=T,i=1,f=24,s=2,v=1,m=1", "/wAA"));
        byte[] interrupted = sequence("a=T,i=2,t=f", path("/tmp/file"));
        assertTrue(Arrays.equals(interrupted, flatten(transport.accept(interrupted))));
    }
}
