package dev.terminal.emulator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves local media in the PTY owner's namespace before output is recorded or sent to the UI. */
public final class KittyGraphicsTransport {
    public interface Resolver {
        byte[] read(char medium, String name, long offset, long size) throws IOException;
    }
    public static final class Output {
        public final List<byte[]> bytes = new ArrayList<>();
        public final List<byte[]> replies = new ArrayList<>();
    }
    private final Resolver resolver;
    private final ByteArrayOutputStream apc = new ByteArrayOutputStream();
    private int state, utf8Remaining; // ground, ESC, APC, APC ESC, other string, other string ESC, oversized APC
    private boolean osc, chunked;
    private static final int FRAME_BYTES = 32 * 1024;

    public KittyGraphicsTransport(Resolver resolver) { this.resolver = resolver; }

    public Output accept(byte[] input) {
        Output result = new Output();
        ByteArrayOutputStream normal = new ByteArrayOutputStream(Math.min(input.length, FRAME_BYTES));
        for (byte raw : input) {
            int b = raw & 255;
            switch (state) {
                case 0:
                    if (utf8Remaining > 0 && (b & 0xc0) == 0x80) { normal.write(b); utf8Remaining--; break; }
                    utf8Remaining = b >= 0xc2 && b <= 0xdf ? 1 : b >= 0xe0 && b <= 0xef ? 2 : b >= 0xf0 && b <= 0xf4 ? 3 : 0;
                    if (b == 27) state = 1;
                    else if (b == 0x9f) { apc.reset(); apc.write(27); apc.write('_'); state = 2; }
                    else {
                        normal.write(b);
                        if (b == 0x90 || b == 0x9d || b == 0x98 || b == 0x9e) { state = 4; osc = b == 0x9d; }
                    }
                    break;
                case 1:
                    if (b == '_') { apc.reset(); apc.write(27); apc.write('_'); state = 2; }
                    else {
                        normal.write(27);
                        if (b == 27) break;
                        normal.write(b); state = b == 'P' || b == ']' || b == 'X' || b == '^' ? 4 : 0; osc = b == ']';
                    }
                    break;
                case 2: case 3:
                    apc.write(b);
                    if (b == 24 || b == 26) { flush(normal, result); append(result, apc.toByteArray()); apc.reset(); state = 0; chunked = false; }
                    else if ((state == 3 && b == '\\') || b == 0x9c) {
                        flush(normal, result); process(apc.toByteArray(), result); apc.reset(); state = 0;
                    } else if (apc.size() > 5124) {
                        flush(normal, result); append(result, apc.toByteArray()); apc.reset(); state = b == 27 ? 7 : 6; chunked = false;
                    } else state = b == 27 ? 3 : 2;
                    break;
                case 4: case 5: case 6: case 7:
                    normal.write(b);
                    boolean other = state == 4 || state == 5;
                    if (b == 24 || b == 26 || b == 0x9c || ((state == 5 || state == 7) && b == '\\') || (other && osc && b == 7)) state = 0;
                    else state = b == 27 ? (other ? 5 : 7) : (other ? 4 : 6);
                    break;
                default: throw new IllegalStateException("parser state");
            }
            if (normal.size() >= FRAME_BYTES) flush(normal, result);
        }
        flush(normal, result);
        return result;
    }

    public Output finish() {
        Output result = new Output();
        if (state == 1) append(result, new byte[]{27});
        else if (state == 2 || state == 3) append(result, apc.toByteArray());
        state = 0; apc.reset(); chunked = false; return result;
    }

    private void process(byte[] sequence, Output result) {
        Map<String, String> keys = new LinkedHashMap<>();
        try {
            if (sequence.length < 5 || sequence[2] != 'G') { append(result, sequence); return; }
            int end = sequence[sequence.length - 1] == (byte) 0x9c ? sequence.length - 1 : sequence.length - 2;
            String command = new String(sequence, 3, end - 3, StandardCharsets.US_ASCII);
            int separator = command.indexOf(';');
            String header = separator < 0 ? command : command.substring(0, separator);
            if (!header.isEmpty()) for (String item : header.split(",", -1)) {
                if (item.length() < 3 || item.charAt(1) != '=' || keys.put(item.substring(0, 1), item.substring(2)) != null)
                    throw new IllegalArgumentException();
            }
            String action = keys.getOrDefault("a", "t"), medium = keys.getOrDefault("t", "d");
            if (action.equals("d")) chunked = false;
            boolean wasChunked = chunked;
            chunked = "1".equals(keys.get("m"));
            if (wasChunked || medium.equals("d") || !(action.equals("t") || action.equals("T") || action.equals("q") || action.equals("f"))) {
                append(result, sequence); return;
            }
            TerminalGraphics.validate(keys);
            if (keys.containsKey("i") && keys.containsKey("I")) throw new IllegalArgumentException();
            if (chunked || separator < 0) throw new IllegalArgumentException();
            byte[] nameBytes = Base64.getDecoder().decode(command.substring(separator + 1));
            String name = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(nameBytes)).toString();
            if (name.isEmpty() || name.indexOf('\0') >= 0) throw new IllegalArgumentException();
            long offset = Long.parseLong(keys.getOrDefault("O", "0")), size = Long.parseLong(keys.getOrDefault("S", "0"));
            if (size > TerminalGraphics.MAX_TRANSFER_BYTES) throw new IOException("E2BIG: media size");
            byte[] data = resolver.read(medium.charAt(0), name, offset, size);
            if (data.length > TerminalGraphics.MAX_TRANSFER_BYTES) throw new IOException("E2BIG: media size");
            keys.put("t", "d"); keys.remove("O"); keys.remove("S"); keys.remove("m");
            StringBuilder rewritten = new StringBuilder();
            for (Map.Entry<String, String> key : keys.entrySet()) {
                if (rewritten.length() != 0) rewritten.append(',');
                rewritten.append(key.getKey()).append('=').append(key.getValue());
            }
            String encoded = Base64.getEncoder().encodeToString(data);
            for (int start = 0; start < Math.max(1, encoded.length()); start += 4096) {
                int stop = Math.min(encoded.length(), start + 4096);
                String metadata = start == 0 ? rewritten.toString() + "," : action.equals("f") ? "a=f," : "";
                append(result, ("\033_G" + metadata + "m=" + (stop < encoded.length() ? "1" : "0") + ";" +
                    encoded.substring(start, stop) + "\033\\").getBytes(StandardCharsets.US_ASCII));
            }
        } catch (IOException error) {
            String message = error.getMessage();
            failure(result, keys, message != null && message.matches("E[A-Z0-9]+: [a-z ]+") ? message : "EIO: media read");
        } catch (IllegalArgumentException error) {
            // Leave malformed commands for the emulator's normal validation; never resolve their paths.
            chunked = false; append(result, sequence);
        }
    }

    private static void failure(Output result, Map<String, String> keys, String message) {
        TerminalGraphics.Result reply = new TerminalGraphics.Result();
        TerminalGraphics.reply(reply, keys, message);
        if (reply.response != null) result.replies.add(reply.response.getBytes(StandardCharsets.US_ASCII));
    }
    private static void flush(ByteArrayOutputStream bytes, Output output) {
        if (bytes.size() > 0) { append(output, bytes.toByteArray()); bytes.reset(); }
    }
    private static void append(Output output, byte[] bytes) {
        for (int start = 0; start < bytes.length; start += FRAME_BYTES)
            output.bytes.add(java.util.Arrays.copyOfRange(bytes, start, Math.min(bytes.length, start + FRAME_BYTES)));
    }
}
