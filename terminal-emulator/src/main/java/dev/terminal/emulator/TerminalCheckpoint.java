package dev.terminal.emulator;

import java.io.*;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Same-build, host-private state transfer. Never deserialize guest files or wire payloads here. */
public final class TerminalCheckpoint {
    private static final int MAGIC = 0x54534331;
    private static final Set<String> CLASSES = Set.of(
        TerminalEmulator.class.getName(), TerminalBuffer.class.getName(), TerminalRow.class.getName(),
        TerminalColors.class.getName(), TerminalGraphics.class.getName(), TerminalImage.class.getName(),
        TerminalHistory.class.getName(), HistoryAnchor.class.getName(), CommandMarker.class.getName(),
        KittyAnimation.class.getName(), KittyKeyboard.class.getName(), SynchronizedOutput.class.getName(),
        "dev.terminal.emulator.TerminalEmulator$SavedScreenState",
        "dev.terminal.emulator.TerminalGraphics$Placement",
        "java.lang.Integer", "java.lang.Long", "java.lang.Number", "java.lang.StringBuilder",
        "java.util.Stack", "java.util.Vector", "java.util.ArrayList", "java.util.ArrayDeque",
        "java.util.HashMap", "java.util.LinkedHashMap", "java.util.Properties", "java.util.Hashtable",
        "[B", "[C", "[I", "[J", "[Z", "[Ljava.lang.Object;", "[Ljava.lang.String;",
        "[Ldev.terminal.emulator.TerminalRow;"
    );

    public static void write(TerminalEmulator emulator, OutputStream stream) throws IOException {
        DataOutputStream header = new DataOutputStream(stream);
        header.writeInt(MAGIC);
        try (ObjectOutputStream out = new ObjectOutputStream(new GZIPOutputStream(header))) {
            out.writeObject(emulator);
        }
    }

    /** The returned graph is unbound: install it with TerminalEmulator.restoreState before parsing. */
    public static TerminalEmulator read(InputStream stream) throws IOException {
        if (new DataInputStream(stream).readInt() != MAGIC) throw new IOException("Unknown terminal checkpoint");
        try (ObjectInputStream in = new ObjectInputStream(new GZIPInputStream(stream)) {
            @Override protected Class<?> resolveClass(ObjectStreamClass descriptor) throws IOException, ClassNotFoundException {
                if (!CLASSES.contains(descriptor.getName())) throw new InvalidClassException("Unsupported checkpoint class", descriptor.getName());
                return super.resolveClass(descriptor);
            }
            @Override protected Class<?> resolveProxyClass(String[] interfaces) throws IOException {
                throw new InvalidClassException("Checkpoint proxies are forbidden");
            }
        }) {
            Object value = in.readObject();
            if (!(value instanceof TerminalEmulator)) throw new IOException("Invalid terminal checkpoint root");
            if (in.read() != -1) throw new IOException("Unexpected checkpoint trailing data");
            return (TerminalEmulator) value;
        } catch (ClassNotFoundException e) { throw new IOException("Incompatible terminal checkpoint", e); }
    }

    private TerminalCheckpoint() {}
}
