package dev.terminal.runtime.vsock

import android.os.ParcelFileDescriptor
import dev.terminal.runtime.api.GuestByteStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Runtime-owned raw vsock stream for explicit copies and localhost forwarding. */
class ParcelFileDescriptorByteStream(
    private val descriptor: ParcelFileDescriptor,
) : GuestByteStream {
    private val closed = AtomicBoolean()
    override val input: InputStream = FileInputStream(descriptor.fileDescriptor)
    override val output: OutputStream = FileOutputStream(descriptor.fileDescriptor)

    override fun shutdownOutput() { android.system.Os.shutdown(descriptor.fileDescriptor, android.system.OsConstants.SHUT_WR) }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // close(fd) alone need not interrupt a kernel read/write already holding
        // the socket. Shutdown wakes both directions before releasing our FD.
        try { runCatching { android.system.Os.shutdown(descriptor.fileDescriptor, android.system.OsConstants.SHUT_RDWR) } }
        finally { descriptor.close() }
    }
}
