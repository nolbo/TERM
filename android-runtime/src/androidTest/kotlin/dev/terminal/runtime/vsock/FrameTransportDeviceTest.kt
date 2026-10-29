package dev.terminal.runtime.vsock

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.terminal.protocol.*
import dev.terminal.runtime.api.GuestConnection
import dev.terminal.runtime.vsock.ParcelFileDescriptorFrameTransport
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FrameTransportDeviceTest {
    @Test fun deadlineInterruptsANativeSocketWriteWithNoPeerConsumption() = runBlocking {
        val sockets = ParcelFileDescriptor.createSocketPair()
        val raw = ParcelFileDescriptorFrameTransport(sockets[0], 0)
        val entered = CompletableDeferred<Unit>()
        val finished = AtomicBoolean()
        val transport = object : GuestConnection by raw {
            override suspend fun send(frame: ProtocolFrame): Result<Unit> {
                entered.complete(Unit)
                try { return raw.send(frame) }
                finally { finished.set(true) }
            }
        }
        val client = ProtocolSessionClient(transport, requestTimeoutMillis = 1_000)
        try {
            withTimeout(5_000) {
                val sending = async { client.write("avf-test", ByteArray(1_000_000)) }
                entered.await()
                delay(50)
                assertFalse("The unread socket must actually backpressure the native writer", finished.get())
                assertTrue(sending.await().exceptionOrNull() is ProtocolRequestTimeoutException)
                assertTrue(finished.get())
                assertEquals(SessionConnectionState.DISCONNECTED, client.connectionState.value)
            }
        } finally { client.close(); sockets[1].close() }
    }

    @Test fun closeWakesABlockedNativeReaderAndIsIdempotent() = runBlocking {
        val sockets = ParcelFileDescriptor.createSocketPair()
        val transport = ParcelFileDescriptorFrameTransport(sockets[0], 0)
        val reader = launch(Dispatchers.IO) { runCatching { transport.incomingFrames().collect() } }
        try {
            delay(50)
            assertTrue(reader.isActive)
            transport.close(); transport.close()
            withTimeout(2_000) { reader.join() }
        } finally { transport.close(); sockets[1].close(); reader.cancel() }
    }
}
