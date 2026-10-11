package dev.terminal.emulator;

public class PasteTest extends TerminalTestCase {
    public void testMultilineCommandsAndIndependentCommandsPreserveStructure() {
        withTerminalSized(80, 24);
        String[] payloads = {
            "printf '%s\\n' \\\n  'hello world'",
            "for word in one two; do\n  echo \"$word\"\ndone",
            "cat <<'EOF'\n\tcontent\n\nEOF",
            "echo first\necho second\necho last",
            "한글\r\n\ttext\n\nlast\r"
        };
        for (boolean alternate : new boolean[] {false, true}) {
            enterString(alternate ? "\033[?1049h" : "\033[?1049l");
            for (boolean bracketed : new boolean[] {true, false}) {
                enterString(bracketed ? "\033[?2004h" : "\033[?2004l");
                for (String payload : payloads) {
                    for (String ending : new String[] {"", "\n", "\r\n", "\n\n"}) {
                        String text = payload + ending;
                        // Historical shell markers must not change application input handling.
                        enterString("\033]133;B\007");
                        mTerminal.paste(text);
                        assertEquals(bracketed ? "\033[200~" + text + "\033[201~"
                            : text.replaceAll("\r?\n", "\r"), mOutput.getOutputAndClear());
                        enterString("\033]133;B\007");
                        assertEquals("", mOutput.getOutputAndClear());
                    }
                }
            }
        }
    }
}
