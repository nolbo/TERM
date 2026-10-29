package dev.terminal.ueprobe;

import android.app.Instrumentation;
import android.os.Bundle;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** A separate test application: starts through Zygote, not adb shell/run-as. */
public final class EntryProbe extends Instrumentation {
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        Process child = null;
        int status = 1;
        try {
            File binary = new File(getTargetContext().getApplicationInfo().nativeLibraryDir,
                "libue-entry-probe.so");
            File output = new File(getTargetContext().getFilesDir(), "entry-probe.txt");
            child = new ProcessBuilder(binary.getAbsolutePath())
                .redirectErrorStream(true).redirectOutput(output).start();
            if (!child.waitFor(15, TimeUnit.SECONDS)) {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
                throw new IllegalStateException("entry probe timed out");
            }
            byte[] bytes = new byte[65536];
            int used = 0;
            try (FileInputStream input = new FileInputStream(output)) {
                while (used < bytes.length) {
                    int count = input.read(bytes, used, bytes.length - used);
                    if (count < 0) break;
                    used += count;
                }
                if (input.read() != -1) throw new IllegalStateException("probe output limit exceeded");
            }
            String text = new String(bytes, 0, used, StandardCharsets.UTF_8);
            if (child.exitValue() == 0 && text.contains("RESULT {\"status\":\"pass\"") &&
                text.contains("inherited_seccomp_mode=2\n")) status = 0;
            else result.putString("failure", "exit=" + child.exitValue() + "; app seccomp mode 2 required");
            result.putString("stream", "app_uid=" + android.os.Process.myUid() + "\n" + text +
                (status == 0 ? "PASS android-inherited-seccomp\n" : "FAIL android-inherited-seccomp (fixture success is insufficient)\n"));
        } catch (Exception failure) {
            result.putString("failure", failure.toString());
        } finally {
            if (child != null && child.isAlive()) child.destroyForcibly();
            finish(status, result);
        }
    }
}
