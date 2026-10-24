package dev.terminal.app

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.terminal.runtime.lifecycle.ConnectionDiagnostics
import dev.terminal.runtime.lifecycle.ConnectionLogStore
import dev.terminal.runtime.vsock.ParcelFileDescriptorByteStream
import dev.terminal.runtime.vsock.ResumableGuestConnection
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native host sockets and the real asynchronous log store; does not claim to exercise AVF vsock. */
@RunWith(AndroidJUnit4::class)
class AvfSessionDiagnosticsDeviceTest {
    @Test fun ownerAndReplayRejectionsPersistDistinctSafeCodesFromNativeSockets() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ConnectionDiagnostics.initialize(context.filesDir)
        val store = ConnectionLogStore(context.filesDir, SystemClock::elapsedRealtimeNanos)
        for (status in listOf(2, 0)) {
            val sockets = ParcelFileDescriptor.createSocketPair()
            val raw = ParcelFileDescriptorByteStream(sockets[0])
            val peer = ParcelFileDescriptorByteStream(sockets[1])
            val owner = "diagnostic-test-$status"
            val server = launch(Dispatchers.IO) {
                val input = DataInputStream(peer.input)
                val output = DataOutputStream(peer.output)
                assertEquals(0x54535231, input.readInt())
                input.readLong(); input.readLong()
                assertEquals(1, input.readUnsignedByte())
                output.writeByte(status); output.flush()
                if (status == 0) {
                    ByteArray(24).also(input::readFully)
                    output.writeInt(0); output.flush() // Reject replay magic after accepting the owner.
                }
            }
            try {
                val failure = withContext(Dispatchers.IO) {
                    runCatching {
                        ResumableGuestConnection(0, connect = { raw }, report = { event, code, error, details ->
                            ConnectionDiagnostics.record(event, owner, code, error, details)
                        }, handshakeTimeoutMillis = 1_000)
                    }.exceptionOrNull()
                }
                assertTrue(failure is IllegalArgumentException)
                server.join()
                val prefix = if (status == 2) "owner_status.error.reply_2" else "replay.handshake.magic.reply_0"
                withTimeout(5_000) {
                    var record: String?
                    do {
                        record = store.snapshot().entries.lastOrNull {
                            it.contains("event=session.attach.failed owner=$owner ")
                        }
                        if (record == null) delay(10)
                    } while (record == null)
                    assertTrue(record.contains("code=$prefix.attempt_0.elapsed_ms_"))
                    assertFalse(record.contains("redacted"))
                    assertFalse(record.contains("Guest lost session replay generation"))
                    assertFalse(record.contains("Invalid replay handshake"))
                }
            } finally { raw.close(); peer.close(); server.cancel() }
        }
    }
}
