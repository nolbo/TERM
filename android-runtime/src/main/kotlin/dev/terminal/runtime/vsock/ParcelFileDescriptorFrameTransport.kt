package dev.terminal.runtime.vsock

import android.os.ParcelFileDescriptor
import dev.terminal.protocol.Protocol
import dev.terminal.protocol.ProtocolFrame
import dev.terminal.runtime.api.GuestConnection
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
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
    private val input = DataInputStream(BufferedInputStream(stream.input))
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
                send(Protocol.read(input))
            }
        } catch (error: EOFException) {
            // A normal VM stop or guest daemon restart closes the vsock stream.
        }
    }.flowOn(Dispatchers.IO)

    override fun close() {
        stream.close()
    }
}
