package dev.terminal.runtime.directlinux

import com.termux.terminal.PtyProcess
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FileInputStream
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

/** Real PTY ownership stays in the test host, independently of guest image replacement. */
internal object DirectLinuxPtyScenario {
    private class Terminal(builder: ProcessBuilder) : Closeable {
        val process = PtyProcess.start(
            builder.command().first(), builder.directory().path, builder.command().toTypedArray(),
            builder.environment().map { (key, value) -> "$key=$value" }.toTypedArray(), 24, 80, 0, 0,
        )
        private val monitor = Object()
        private val output = ByteArrayOutputStream()
        private val descriptor = (process.inputStream as FileInputStream).fd
        @Volatile private var reading = true
        private var ended = false
        private var readError: Exception? = null
        private var previousCommand = 0
        private val exit = FutureTask { process.waitFor() }
        private val reader = thread(name = "direct-linux-pty-reader", isDaemon = true) {
            try {
                Os.fcntlInt(descriptor, OsConstants.F_SETFL,
                    Os.fcntlInt(descriptor, OsConstants.F_GETFL, 0) or OsConstants.O_NONBLOCK)
                val poll = StructPollfd().apply { fd = descriptor; events = OsConstants.POLLIN.toShort() }
                val buffer = ByteArray(2048)
                while (reading) {
                    if (Os.poll(arrayOf(poll), 50) <= 0) continue
                    val count = try { Os.read(descriptor, buffer, 0, buffer.size) } catch (error: ErrnoException) {
                        if (error.errno == OsConstants.EAGAIN || error.errno == OsConstants.EINTR) continue
                        throw error
                    }
                    if (count <= 0) break
                    synchronized(monitor) {
                        check(output.size() + count <= 65536) { "Unbounded PTY output" }
                        output.write(buffer, 0, count)
                        monitor.notifyAll()
                    }
                }
            } catch (error: Exception) {
                synchronized(monitor) { readError = error }
            } finally {
                synchronized(monitor) { ended = true; monitor.notifyAll() }
            }
        }
        init { thread(name = "direct-linux-pty-wait", isDaemon = true) { exit.run() } }

        fun mark(): Int = synchronized(monitor) { output.size() }
        fun textSince(after: Int): String = synchronized(monitor) {
            val bytes = output.toByteArray()
            String(bytes, after, bytes.size - after, Charsets.UTF_8).replace("\r", "")
        }
        fun send(text: String) { process.outputStream.write(text.toByteArray(Charsets.UTF_8)); process.outputStream.flush() }
        fun expect(pattern: Regex, after: Int = 0): String {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            synchronized(monitor) {
                while (true) {
                    val bytes = output.toByteArray()
                    val text = String(bytes, after, bytes.size - after, Charsets.UTF_8).replace("\r", "")
                    pattern.find(text)?.let { return it.value }
                    check(!ended && System.nanoTime() < deadline) {
                        "PTY did not produce $pattern; read error=$readError\n${output.toString("UTF-8").takeLast(8192)}"
                    }
                    monitor.wait(50)
                }
            }
        }
        fun command(command: String, expected: String): String {
            return matchCommand(command, Regex("(?:^|\n)${Regex.escape(expected)}\n"))
        }
        fun submit(command: String): Int {
            expect(Regex("(?:^|\n)(?:DL> |[#$] )"), previousCommand)
            val after = mark()
            previousCommand = after
            send("$command\n")
            return after
        }
        fun matchCommand(command: String, pattern: Regex): String = expect(pattern, submit(command))
        fun awaitExit(): Int = exit.get(8, TimeUnit.SECONDS)
        fun hangup() {
            // A blocked read can hold the master alive after close. Stop the
            // bounded nonblocking reader before testing the last master close.
            reading = false
            reader.join(5000)
            check(!reader.isAlive)
            process.close()
        }
        override fun close() {
            process.terminate(9)
            reading = false
            process.close()
            exit.get(5, TimeUnit.SECONDS)
            reader.join(5000)
            check(!reader.isAlive) { "PTY reader did not stop" }
        }
    }

    fun verify(launch: (String, Array<out String>) -> ProcessBuilder): JSONObject {
        val checks = JSONArray()
        Terminal(launch("/bin/sh", arrayOf("-i"))).use { terminal ->
            terminal.command("PS1='DL> '; printf 'READY shell\\n'", "READY shell")
            val identity = terminal.matchCommand("printf 'SHELL_PID:%s\\n' \"\$\$\"", Regex("(?:^|\n)SHELL_PID:[0-9]+\n"))
            terminal.command("printf 'TERM:%s\\n' \"\$TERM\"", "TERM:xterm-256color")
            for (kind in listOf("static", "dynamic")) {
                terminal.command("/bin/tty-$kind check 24 80", "PASS tty foreground/termios/winsize/setsid/SIGTTOU")
            }
            checks.put("static/dynamic controlling terminal, foreground group, setsid and SIGTTOU")
            terminal.command("/bin/stty -F /dev/tty size", "24 80")
            terminal.command("/bin/stty -echo; printf 'READY input\\n'; IFS= read -r answer; /bin/stty echo; printf 'INPUT<%s>\\n' \"\$answer\"", "READY input")
            val input = terminal.mark()
            terminal.send("hello 한글\n")
            terminal.expect(Regex("(?:^|\n)INPUT<hello 한글>\n"), input)
            check(terminal.textSince(input).lineSequence().none { it == "hello 한글" }) { "stty -echo did not suppress input echo" }
            checks.put("GNU stty, termios changes and UTF-8 input/output")
            terminal.command("/bin/tty-dynamic resize", "READY resize")
            val resized = terminal.mark()
            terminal.process.resize(37, 111, 0, 0)
            terminal.expect(Regex("(?:^|\n)PASS SIGWINCH resize 37 111\n"), resized)
            terminal.command("/bin/stty -F /dev/tty size", "37 111")
            checks.put("host resize and foreground SIGWINCH")
            terminal.command("/bin/tty-dynamic interrupt", "READY interrupt")
            terminal.send("\u0003")
            // Interactive dash cancels the remaining command list on Ctrl-C.
            terminal.command("printf 'INT_STATUS:%s\\n' \"\$?\"", "INT_STATUS:130")
            checks.put("Ctrl-C interrupts foreground child while shell survives")
            terminal.command("/bin/tty-dynamic stop", "READY stop")
            val stopped = terminal.mark()
            terminal.send("\u001a")
            terminal.expect(Regex("Stopped"), stopped)
            terminal.matchCommand("bg %1; jobs", Regex("Running"))
            terminal.command("kill %1; wait %1; printf 'JOB_STATUS:%s\\n' \"\$?\"", "JOB_STATUS:143")
            checks.put("Ctrl-Z, jobs, bg, explicit job termination and wait status")
            check(terminal.matchCommand("printf 'SHELL_PID:%s\\n' \"\$\$\"", Regex("(?:^|\n)SHELL_PID:[0-9]+\n")) == identity)
            terminal.submit("exit 23")
            check(terminal.awaitExit() == 23)
            checks.put("same shell survives interactions; explicit exit status 23")
        }
        var hangupExit: Int
        var hangupLeader: Boolean
        Terminal(launch("/bin/tty-dynamic", arrayOf("hangup"))).use { terminal ->
            val role = terminal.expect(Regex("(?:^|\n)READY hangup leader:[01]\n"))
            hangupLeader = role.trim().endsWith("1")
            val started = System.nanoTime()
            terminal.hangup()
            hangupExit = terminal.awaitExit()
            check(hangupExit == if (hangupLeader) -1 else 78) {
                "Unexpected hangup status $hangupExit after ${(System.nanoTime() - started) / 1e6} ms"
            }
        }
        checks.put("master closure: session leader SIGHUP, or nonleader HUP/EOF/ENXIO")
        return JSONObject().put("checks", checks).put("master_close_exit", hangupExit)
            .put("hangup_guest_is_session_leader", hangupLeader)
            .put("ui_lifecycle_verified", false)
    }
}
