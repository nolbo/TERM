package dev.terminal.app

import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.protocol.SessionController
import dev.terminal.protocol.SessionEvent
import dev.terminal.runtime.api.RuntimeConfig
import dev.terminal.runtime.api.StopMode
import dev.terminal.runtime.native.NativeSessionController
import dev.terminal.runtime.rootless.RootlessDebianBackend
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Run on a provisioned ARM64 test device twice, with expectedAccess=false/true.
 * Create /sdcard/Download/droid-storage-test-fixture.txt containing storage-fixture first.
 * Permission changes are performed by the operator, never by the test itself.
 */
@RunWith(AndroidJUnit4::class)
class SharedStorageAccessTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun requireExplicitPermissionScenario() {
        assumeNotNull(InstrumentationRegistry.getArguments().getString("expectedAccess"))
    }

    private val expectedAccess get() = requireNotNull(
        InstrumentationRegistry.getArguments().getString("expectedAccess"),
    ) { "Pass expectedAccess=true or false explicitly" }.toBooleanStrict()

    @Test fun nativeSharedStorageReadWrite() = runBlocking {
        assertEquals(expectedAccess, Environment.isExternalStorageManager())
        val root = File(context.cacheDir, "storage-test-${UUID.randomUUID()}")
        File(root, "tmp").mkdirs()
        val controller = NativeSessionController(root)
        try { verify(controller, "/system/bin/sh") } finally {
            controller.close()
            root.deleteRecursively()
        }
    }

    @Test fun rootlessSharedStorageReadWrite() = runBlocking {
        assertEquals(expectedAccess, Environment.isExternalStorageManager())
        check(File(context.filesDir, "rootless/debian/rootfs/bin/sh").exists()) {
            "Provision Rootless Debian before this test"
        }
        val backend = RootlessDebianBackend(context)
        try {
            backend.prepare(RuntimeConfig(imageDirectory = "")).getOrThrow()
            backend.start().getOrThrow()
            verify(requireNotNull(backend.sessionController()), "/bin/sh")
        } finally { backend.stop(StopMode.FORCE).getOrThrow() }
    }

    private suspend fun verify(controller: SessionController, shell: String) = kotlinx.coroutines.coroutineScope {
        val transcript = StringBuilder()
        val completed = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(20_000) {
                controller.events.first { event ->
                    if (event is SessionEvent.Output) transcript.append(event.bytes.toString(Charsets.UTF_8))
                    transcript.contains("\nSTORAGE_PASS") || transcript.contains("\nSTORAGE_FAIL")
                }
            }
        }
        val session = controller.create(shell, "/root", emptyMap(), 24u, 100u).getOrThrow()
        val file = "/sdcard/Download/droid-storage-test-${UUID.randomUUID()}.txt"
        val read = "[ \"\$(cat /sdcard/Download/droid-storage-test-fixture.txt 2>/dev/null)\" = storage-fixture ]"
        val write = "(printf storage-write > $file) 2>/dev/null"
        val check = if (expectedAccess) {
            "$read && $write && [ \"\$(cat $file)\" = storage-write ] && rm $file"
        } else {
            // Scoped storage can still allow creation of app-owned files without all-files access.
            "! $read && ! (printf storage-write >> /sdcard/Download/droid-storage-test-fixture.txt) 2>/dev/null"
        }
        controller.write(session.id,
            "if $check; then printf '\\nSTORAGE_%s\\n' PASS; else printf '\\nSTORAGE_%s\\n' FAIL; fi\n".toByteArray(),
        ).getOrThrow()
        try {
            completed.await()
            check(transcript.contains("\nSTORAGE_PASS")) { transcript.toString() }
        } finally { controller.terminate(session.id, 9).getOrThrow() }
    }
}
