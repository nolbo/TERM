package dev.terminal.app

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.terminal.protocol.FrameKind
import dev.terminal.protocol.Protocol
import dev.terminal.protocol.ProtocolFrame
import dev.terminal.protocol.Service
import dev.terminal.runtime.vsock.ParcelFileDescriptorByteStream
import dev.terminal.runtime.vsock.ParcelFileDescriptorFrameTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.toList
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AvfFrameDecodeDeviceTest {
    @Test fun fragmentedNativeReadsRetainFrameBoundariesAndOwnedPayloads() = runBlocking {
        val pair = ParcelFileDescriptor.createSocketPair()
        val connection = ParcelFileDescriptorFrameTransport(pair[0], 0)
        val peer = ParcelFileDescriptorByteStream(pair[1])
        val expected = listOf(
            ProtocolFrame(service = Service.SESSION, kind = FrameKind.STREAM, requestId = 0,
                payload = ByteArray(65_537) { (it * 17).toByte() }),
            ProtocolFrame(service = Service.BRIDGE, kind = FrameKind.RESPONSE, requestId = Long.MAX_VALUE),
        )
        try {
            withTimeout(5_000) {
                val writer = launch(Dispatchers.IO) {
                    for (frame in expected) {
                        val wire = Protocol.encode(frame)
                        var offset = 0
                        while (offset < wire.size) {
                            val count = minOf(509, wire.size - offset)
                            peer.output.write(wire, offset, count)
                            offset += count
                        }
                    }
                    peer.shutdownOutput()
                }
                val actual = connection.incomingFrames().toList()
                writer.join()
                assertEquals(expected.size, actual.size)
                expected.zip(actual).forEach { (sent, received) ->
                    assertArrayEquals(Protocol.encode(sent), Protocol.encode(received))
                }
                actual.first().payload.fill(0)
                assertEquals(0, actual.last().payload.size)
            }
        } finally { connection.close(); peer.close() }
    }
}
