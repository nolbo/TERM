package dev.terminal.emulator;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** PTY-side remote-control framing and keyboard-mode observation. Never executes a command. */
public final class KittyControlStream {
    public static final int MAX_COMMAND_BYTES = 64 * 1024;
    private static final byte[] PREFIX = "@kitty-cmd".getBytes(StandardCharsets.US_ASCII);
    public static final class Part {
        public final byte[] bytes;
        public final String command;
        private Part(byte[] bytes, String command) { this.bytes = bytes; this.command = command; }
    }
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private final StringBuilder csi = new StringBuilder();
    private final KittyKeyboard main = new KittyKeyboard(), alternate = new KittyKeyboard();
    private boolean altScreen, osc, dcsC1;
    private int state, utf8;
    // ground, ESC, DCS prefix, remote, remote ESC, other string, other ESC, discard, discard ESC, CSI
    public KittyKeyboard keyboard() { return altScreen ? alternate : main; }

    public List<Part> accept(byte[] bytes) {
        List<Part> parts = new ArrayList<>();
        ByteArrayOutputStream normal = new ByteArrayOutputStream();
        for (byte raw : bytes) {
            int b = raw & 255;
            boolean continuation = utf8 > 0 && (b & 0xc0) == 0x80;
            utf8 = continuation ? utf8 - 1 : b >= 0xc2 && b <= 0xdf ? 1 : b >= 0xe0 && b <= 0xef ? 2 : b >= 0xf0 && b <= 0xf4 ? 3 : 0;
            switch (state) {
                case 0:
                    if (continuation) { normal.write(b); break; }
                    if (b == 27) state = 1;
                    else if (b == 0x90) { pending.reset(); state = 2; dcsC1 = true; }
                    else {
                        normal.write(b);
                        if (b == 0x9b) { state = 9; csi.setLength(0); }
                        else if (b == 0x9d || b == 0x9f || b == 0x98 || b == 0x9e) { state = 5; osc = b == 0x9d; }
                    }
                    break;
                case 1:
                    if (b == 'P') { pending.reset(); state = 2; dcsC1 = false; }
                    else {
                        normal.write(27);
                        if (b == 27) break;
                        normal.write(b); state = 0;
                        if (b == '[') { state = 9; csi.setLength(0); }
                        else if (b == ']' || b == '_' || b == 'X' || b == '^') { state = 5; osc = b == ']'; }
                        else if (b == 'c') { main.reset(); alternate.reset(); altScreen = false; }
                    }
                    break;
                case 2:
                    if (b == PREFIX[pending.size()]) {
                        pending.write(b);
                        if (pending.size() == PREFIX.length) { pending.reset(); state = 3; }
                    } else {
                        if (dcsC1) normal.write(0x90); else { normal.write(27); normal.write('P'); }
                        byte[] prefix = pending.toByteArray(); normal.write(prefix, 0, prefix.length); normal.write(b);
                        pending.reset(); osc = false;
                        state = b == 24 || b == 26 || b == 0x9c ? 0 : b == 27 ? 6 : 5;
                    }
                    break;
                case 3: case 4:
                    if (b == 24 || b == 26) { pending.reset(); state = 0; }
                    else if (b == 0x9c && !continuation || state == 4 && b == '\\') {
                        flush(normal, parts);
                        String command;
                        try { command = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(pending.toByteArray())).toString(); }
                        catch (Exception error) { command = ""; }
                        parts.add(new Part(null, command)); pending.reset(); state = 0;
                    } else if (state == 4 || pending.size() >= MAX_COMMAND_BYTES) {
                        pending.reset(); state = b == 27 ? 8 : 7;
                    } else if (b == 27) state = 4;
                    else pending.write(b);
                    break;
                case 5: case 6:
                    normal.write(b);
                    if (b == 24 || b == 26 || b == 0x9c && !continuation || state == 6 && b == '\\' || osc && b == 7) state = 0;
                    else state = b == 27 ? 6 : 5;
                    break;
                case 7: case 8:
                    if (b == 24 || b == 26) state = 0;
                    else if (b == 0x9c && !continuation || state == 8 && b == '\\') {
                        flush(normal, parts); parts.add(new Part(null, "")); state = 0;
                    } else state = b == 27 ? 8 : 7;
                    break;
                case 9:
                    if (b != 27) normal.write(b);
                    if (b >= 0x40 && b <= 0x7e) { observeCsi(csi.toString(), (char) b); state = 0; }
                    else if (b == 24 || b == 26) state = 0;
                    else if (b == 27) { state = 1; } // ESC cancels an incomplete CSI.
                    else if (b >= 0x20 && b <= 0x3f && csi.length() < 128) csi.append((char) b);
                    else if (csi.length() >= 128) csi.setCharAt(0, '!');
                    break;
                default: throw new IllegalStateException("control parser state");
            }
            if (normal.size() >= 32 * 1024) flush(normal, parts);
        }
        flush(normal, parts);
        return parts;
    }

    /** Preserve an incomplete ordinary escape prefix at EOF; incomplete requests never execute. */
    public byte[] finish() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (state == 1) bytes.write(27);
        else if (state == 2) {
            if (dcsC1) bytes.write(0x90); else { bytes.write(27); bytes.write('P'); }
            byte[] prefix = pending.toByteArray(); bytes.write(prefix, 0, prefix.length);
        }
        pending.reset(); state = 0;
        return bytes.toByteArray();
    }

    private void observeCsi(String params, char finalByte) {
        try {
            if ((finalByte == 'h' || finalByte == 'l') && params.startsWith("?")) {
                for (String n : params.substring(1).split(";"))
                    if (n.equals("47") || n.equals("1047") || n.equals("1049")) altScreen = finalByte == 'h';
            } else if (finalByte == 'u' && !params.isEmpty()) {
                char operation = params.charAt(0);
                String[] values = params.substring(1).split(";", -1);
                if (values.length > 2) return;
                int first = values[0].isEmpty() ? operation == '<' ? 1 : 0 : Integer.parseInt(values[0]);
                if (first < 0) return;
                if (operation == '>') keyboard().push(first);
                else if (operation == '<') keyboard().pop(first);
                else if (operation == '=') keyboard().set(first, values.length < 2 || values[1].isEmpty() ? 1 : Integer.parseInt(values[1]));
            }
        } catch (NumberFormatException ignored) { /* Malformed controls cannot alter the observed mode. */ }
    }

    private static void flush(ByteArrayOutputStream bytes, List<Part> parts) {
        if (bytes.size() != 0) { parts.add(new Part(bytes.toByteArray(), null)); bytes.reset(); }
    }
}
