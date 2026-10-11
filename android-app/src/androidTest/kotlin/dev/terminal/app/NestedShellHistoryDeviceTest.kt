package dev.terminal.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.terminal.TerminalCheckpoint
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.ipc.ITerminalStateListener
import dev.terminal.runtime.lifecycle.VmRuntimeService
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Uses provisioned images, creates only test sessions, and never edits user/server rc files. */
class NestedShellHistoryDeviceTest {
    @Test fun loginSuKeepsOtherUsersCommandsInAuthoritativeHistory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val distributions = listOf("ROOTLESS" to "debian", "ROOTLESS_UBUNTU" to "ubuntu")
            .filter { (_, directory) -> File(context.filesDir, "rootless/$directory/rootfs/usr/bin/su").exists() }
        assumeTrue("Requires an installed Debian or Ubuntu image with su", distributions.isNotEmpty())
        val connected = CountDownLatch(1)
        lateinit var service: IRuntimeService
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = IRuntimeService.Stub.asInterface(binder)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        fun ids(): Set<String> = JSONArray(service.sessionsJson).let { array ->
            (0 until array.length()).map { array.getJSONObject(it).getString("id") }.toSet()
        }
        fun await(message: String, condition: () -> Boolean) {
            val until = SystemClock.uptimeMillis() + 30_000
            while (SystemClock.uptimeMillis() < until) {
                if (condition()) return
                SystemClock.sleep(50)
            }
            fail(message)
        }
        assertTrue(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue(connected.await(10, TimeUnit.SECONDS))
            for ((runtime, _) in distributions) {
                val lock = Any()
                val transcript = StringBuilder()
                var mirror: TerminalEmulator? = null
                var error: String? = null
                val output = object : TerminalOutput() {
                    override fun write(data: ByteArray, offset: Int, count: Int) = Unit
                    override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
                    override fun onCopyTextToClipboard(text: String?) = Unit
                    override fun onPasteTextFromClipboard() = Unit
                    override fun onBell() = Unit
                    override fun onColorsChanged() = Unit
                }
                val listener = object : ITerminalStateListener.Stub() {
                    override fun onCheckpoint(state: ParcelFileDescriptor) {
                        val checkpoint = ParcelFileDescriptor.AutoCloseInputStream(state).use(TerminalCheckpoint::read)
                        synchronized(lock) {
                            mirror = TerminalEmulator(output, 80, 24, 0, 0, 1000, null).apply { restoreState(checkpoint) }
                        }
                    }
                    override fun onOutput(bytes: ByteArray) = synchronized(lock) {
                        transcript.append(bytes.toString(Charsets.UTF_8))
                        mirror?.appendMirror(bytes, bytes.size)
                        Unit
                    }
                    override fun onResize(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) = synchronized(lock) {
                        mirror?.resize(columns, rows, cellWidth, cellHeight)
                        Unit
                    }
                    override fun onExited(code: Int) = Unit
                    override fun onError(message: String) = synchronized(lock) { error = message }
                }
                val previous = ids()
                service.createSession(runtime, "/bin/bash", "", 24, 80)
                var id: String? = null
                await("$runtime session was not created") { id = (ids() - previous).singleOrNull(); id != null }
                val sessionId = requireNotNull(id)
                fun prompts() = synchronized(lock) { transcript.split("\u001b[?2004h").size - 1 }
                fun command(value: String) {
                    service.writeSessionInput(sessionId, "$value\n".toByteArray())
                    await("$runtime did not record $value: $error") {
                        synchronized(lock) { mirror?.history?.markers?.lastOrNull()?.let {
                            it.commandText == value && !it.isRunning
                        } == true }
                    }
                }
                try {
                    service.attachTerminal(sessionId, listener, 24, 80, 0, 0, "")
                    await("$runtime initial prompt missing: $error") { synchronized(lock) { mirror != null } && prompts() > 0 }
                    val beforeSu = prompts()
                    val su = "su --login --shell /bin/bash nobody"
                    service.writeSessionInput(sessionId, "$su\n".toByteArray())
                    await("$runtime su prompt missing: $error") { prompts() > beforeSu }
                    command("whoami")
                    synchronized(lock) {
                        val marker = requireNotNull(mirror).history.markers.last()
                        assertEquals("$runtime must really switch users", "nobody", marker.outputText?.trim())
                        assertNull("Login su must not inherit the original OSC hooks", marker.exitCode)
                    }
                    command("false")
                    synchronized(lock) {
                        val marker = requireNotNull(mirror).history.markers.last()
                        assertNull(marker.exitCode)
                        assertFalse("An unknown exit code must not imply failure", marker.isFailed)
                    }
                    // Reattach from the service checkpoint, rather than relying on live mirror parsing.
                    service.detachTerminal(sessionId, listener)
                    synchronized(lock) { mirror = null }
                    service.attachTerminal(sessionId, listener, 24, 80, 0, 0, "")
                    await("$runtime checkpoint missing") { synchronized(lock) { mirror != null } }
                    synchronized(lock) {
                        val commands = requireNotNull(mirror).history.markers.map { it.commandText }
                        assertTrue(commands.containsAll(listOf(su, "whoami", "false")))
                    }
                    val beforeExit = prompts()
                    service.writeSessionInput(sessionId, "exit\n".toByteArray())
                    await("$runtime did not return from su") { prompts() > beforeExit }
                    command("true")
                    synchronized(lock) {
                        assertEquals(Integer.valueOf(0), requireNotNull(mirror).history.markers.last().exitCode)
                    }
                } finally {
                    runCatching { service.detachTerminal(sessionId, listener) }
                    service.terminateSession(sessionId)
                }
            }
        } finally {
            context.unbindService(connection)
        }
    }
}
