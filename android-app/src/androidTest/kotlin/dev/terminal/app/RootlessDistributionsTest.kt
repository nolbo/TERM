package dev.terminal.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.ipc.IRuntimeStateListener
import dev.terminal.runtime.lifecycle.VmRuntimeService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in device check: installs approved images over the network, retaining their rootfs.
 * Terminates only sessions created by this test. Set -e rootlessPackages true to also
 * update package indexes and install a small package in the new environments.
 */
@RunWith(AndroidJUnit4::class)
class RootlessDistributionsTest {
    @Test fun ubuntuAndArchInstallAndKeepIndependentSessions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connected = CountDownLatch(1)
        lateinit var service: IRuntimeService
        val output = ConcurrentHashMap<String, StringBuilder>()
        val errors = StringBuilder()
        val created = mutableListOf<String>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = IRuntimeService.Stub.asInterface(binder)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        val listener = object : IRuntimeStateListener.Stub() {
            override fun onSessionTaskCompletionsChanged(completionsJson: String) = Unit
            override fun onTerminalOutput(id: String, bytes: ByteArray, replay: Boolean) {
                val buffer = output.computeIfAbsent(id) { StringBuilder() }
                synchronized(buffer) { buffer.append(bytes.toString(Charsets.UTF_8)) }
            }
            override fun onRuntimeChoicesChanged() = Unit
            override fun onSessionFocusRequested(sessionId: String) = Unit
            override fun onSessionsChanged(revision: Long, json: String) = Unit
            override fun onSessionExited(id: String, code: Int) = Unit
            override fun onSessionTerminationFailed(id: String, message: String) = Unit
            override fun onRootlessProgress(runtimeType: String, phase: String, detail: String) = Unit
            override fun onOperationError(operation: String, message: String) {
                synchronized(errors) { errors.append("$operation: $message\n") }
            }
        }
        fun transcript(id: String): String = output[id]?.let { synchronized(it) { it.toString() } }.orEmpty()
        fun await(timeout: Long = 30_000, condition: () -> Boolean) {
            val until = SystemClock.uptimeMillis() + timeout
            while (SystemClock.uptimeMillis() < until) {
                if (condition()) return
                SystemClock.sleep(100)
            }
            fail("Timed out. Errors: $errors\n" + created.joinToString("\n") { transcript(it).takeLast(4000) })
        }
        fun command(id: String, command: String, timeout: Long = 60_000) {
            val token = UUID.randomUUID().toString()
            // Separate printf arguments so the echoed input cannot match the completion marker.
            service.writeSessionInput(id, ("$command; code=\$?; printf '\\n%s%s:%s\\n' 'DONE-' '$token' \"\$code\"\n").toByteArray())
            await(timeout) { transcript(id).contains("DONE-$token:") }
            assertTrue(transcript(id).takeLast(4000), transcript(id).contains("DONE-$token:0"))
        }
        assertTrue(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue(connected.await(10, TimeUnit.SECONDS))
            service.registerListener(listener)

            for ((runtime, distroId) in listOf("ROOTLESS_UBUNTU" to "ubuntu", "ROOTLESS_ARCH" to "archarm")) {
                val previous = sessions(service.sessionsJson).keys
                val profile = SessionProfiles(context).builtIns().first { it.runtime.wireName == runtime }
                service.createSessionFromSpecWithInstallationConsent(SessionProfiles(context).resolve(profile).toJson().toString(),
                    java.util.UUID.randomUUID().toString(), 24, 80)
                var id: String? = null
                await(240_000) {
                    id = sessions(service.sessionsJson).entries.firstOrNull { it.key !in previous && it.value == runtime }?.key
                    id != null
                }
                val sessionId = requireNotNull(id)
                created += sessionId
                service.attachSession(sessionId)
                command(sessionId, "stty -echo; . /etc/os-release; test \"\$ID\" = '$distroId' && test \"\$HOME\" = /root && mkdir -p /workspace && test -d /workspace")
                command(sessionId, "ROOTLESS_TEST_VALUE='$runtime'; test -z \"\${ROOTLESS_OTHER_TEST_VALUE:-}\"")
                service.resizeSession(sessionId, 37, 91)
                command(sessionId, "test \"\$(stty size)\" = '37 91'")
                service.detachSession(sessionId)
                service.attachSession(sessionId)
                command(sessionId, "test \"\$ROOTLESS_TEST_VALUE\" = '$runtime'")
                if (InstrumentationRegistry.getArguments().getString("rootlessPackages") == "true") {
                    val packages = if (runtime == "ROOTLESS_UBUNTU")
                        "apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y hello && hello"
                    else "pacman -Syu --noconfirm && pacman -S --noconfirm --needed jq && jq --version"
                    command(sessionId, packages, 600_000)
                } else {
                    command(sessionId, if (runtime == "ROOTLESS_UBUNTU") "apt-get --version" else "pacman --version")
                }
            }
            val before = sessions(service.sessionsJson)
            service.terminateSession(created.first())
            await { created.first() !in sessions(service.sessionsJson) }
            assertEquals(before.keys - created.first(), sessions(service.sessionsJson).keys)
            command(created.last(), "test \"\$ROOTLESS_TEST_VALUE\" = ROOTLESS_ARCH")
        } finally {
            if (connected.count == 0L) {
                created.forEach { runCatching { service.terminateSession(it) } }
                runCatching { service.unregisterListener(listener) }
            }
            context.unbindService(connection)
        }
    }

    private fun sessions(json: String): Map<String, String> = JSONArray(json).let { array ->
        (0 until array.length()).associate { index ->
            array.getJSONObject(index).let { it.getString("id") to it.getString("runtime") }
        }
    }
}
