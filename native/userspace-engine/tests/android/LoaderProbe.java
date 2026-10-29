package dev.terminal.ueprobe;

import android.app.Instrumentation;
import android.os.Bundle;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Executes APK-owned loader + glibc data fixtures from an actual Zygote app. */
public final class LoaderProbe extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    private String read(File file) throws Exception {
        byte[] bytes = new byte[65536];
        int used = 0;
        try (InputStream input = new FileInputStream(file)) {
            while (used < bytes.length) {
                int count = input.read(bytes, used, bytes.length - used);
                if (count < 0) break;
                used += count;
            }
            if (input.read() != -1) throw new IllegalStateException("output limit exceeded");
        }
        return new String(bytes, 0, used, StandardCharsets.UTF_8);
    }

    private String execute(List<String> command, boolean trap, int expected) throws Exception {
        File output = new File(getTargetContext().getFilesDir(), "loader-output.txt");
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output);
        builder.environment().clear();
        builder.environment().put("LC_ALL", "C");
        builder.environment().put("UE_FIXTURE", "preserved=value");
        if (trap) builder.environment().put("UE_TRAP_EXPECTED", "1");
        Process process = builder.start();
        try {
            if (!process.waitFor(15, TimeUnit.SECONDS)) throw new IllegalStateException("loader timed out");
            String text = read(output);
            if (process.exitValue() != expected)
                throw new IllegalStateException("exit=" + process.exitValue() + ": " + text);
            return text;
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
        }
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        StringBuilder report = new StringBuilder();
        int status = 1;
        try {
            File nativeDirectory = new File(getTargetContext().getApplicationInfo().nativeLibraryDir);
            String entry = execute(List.of(new File(nativeDirectory, "libue-entry-probe.so").getAbsolutePath()), false, 0);
            boolean policy = entry.contains("inherited_seccomp_mode=2\n");
            report.append(policy ? "inherited_seccomp_mode=2\n" : "FAIL inherited seccomp mode 2 absent\n");
            for (String name : List.of("static-exec", "static-pie")) {
                File fixture = new File(getTargetContext().getFilesDir(), name);
                try (InputStream input = getTargetContext().getAssets().open(name);
                     FileOutputStream output = new FileOutputStream(fixture)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
                try {
                    if (!fixture.setExecutable(true, true)) throw new IllegalStateException("fixture mode");
                    for (boolean trap : new boolean[] {false, true}) {
                        List<String> command = new ArrayList<>();
                        command.add(new File(nativeDirectory, "libue-loader-probe.so").getAbsolutePath());
                        if (trap) command.add("--probe-trap");
                        command.addAll(List.of(fixture.getAbsolutePath(), "two words", "", "한글"));
                        String text = execute(command, trap, 37);
                        if (!text.startsWith("PASS argv env constructor TLS BSS auxv heap FD-cleanup\n"))
                            throw new IllegalStateException("fixture marker absent: " + text);
                        report.append("PASS ").append(name).append(trap ? " trap\n" : " native-syscalls\n");
                    }
                } finally { fixture.delete(); }
            }
            if (policy) status = 0;
            else result.putString("failure", "fixture success is insufficient: app seccomp mode 2 required");
        } catch (Exception failure) {
            result.putString("failure", failure.toString());
        } finally {
            result.putString("stream", report.toString());
            finish(status, result);
        }
    }
}
