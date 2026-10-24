package dev.terminal.runtime.vsock

import android.os.ParcelFileDescriptor
import dev.terminal.protocol.Protocol
import dev.terminal.protocol.ProtocolFrame
import dev.terminal.runtime.api.GuestConnection
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Converts AVF's system-API `VirtualMachine.connectVsock()` file descriptor to
 * the product protocol transport. This class does not create a VM and never
 * exposes the raw descriptor to UI callers.
 */
class ParcelFileDescriptorFrameTransport(
    descriptor: ParcelFileDescriptor,
    override val guestCid: Int,
) : GuestConnection {
    private val writeMutex = Mutex()
    private val stream = ParcelFileDescriptorByteStream(descriptor)
    private val input = BufferedInputStream(stream.input)
    private val output = BufferedOutputStream(stream.output)

    override suspend fun send(frame: ProtocolFrame): Result<Unit> = runCatching {
        writeMutex.withLock {
            runInterruptible(Dispatchers.IO) {
                output.write(Protocol.encode(frame))
                output.flush()
            }
        }
    }

    override fun incomingFrames(): Flow<ProtocolFrame> = channelFlow {
        try {
            while (isActive) {
                val header = ByteArray(Protocol.HEADER_SIZE)
                input.readExactly(header)
                val payloadSize = ((header[20].toInt() and 0xff) shl 24) or
                    ((header[21].toInt() and 0xff) shl 16) or
                    ((header[22].toInt() and 0xff) shl 8) or
                    (header[23].toInt() and 0xff)
                require(payloadSize in 0..Protocol.MAX_PAYLOAD_BYTES) { "Invalid guest frame length" }
                val wireFrame = header + ByteArray(payloadSize)
                input.readExactly(wireFrame, Protocol.HEADER_SIZE, payloadSize)
                send(Protocol.decode(wireFrame))
            }
        } catch (error: EOFException) {
            // A normal VM stop or guest daemon restart closes the vsock stream.
        }
    }.flowOn(Dispatchers.IO)

    override fun close() {
        stream.close()
    }

    private fun BufferedInputStream.readExactly(target: ByteArray, offset: Int = 0, length: Int = target.size) {
        var nextOffset = offset
        val end = offset + length
        while (nextOffset < end) {
            val count = read(target, nextOffset, end - nextOffset)
            if (count < 0) throw EOFException("Guest vsock stream closed")
            nextOffset += count
        }
    }
}
