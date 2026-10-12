package dev.terminal.runtime.vsock

import android.os.ParcelFileDescriptor
import dev.terminal.runtime.api.GuestByteStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Internal raw vsock wrapper for localhost forwarding; descriptor ownership stays in runtime. */
class ParcelFileDescriptorByteStream(
    private val descriptor: ParcelFileDescriptor,
) : GuestByteStream {
    override val input: InputStream = FileInputStream(descriptor.fileDescriptor)
    override val output: OutputStream = FileOutputStream(descriptor.fileDescriptor)

    override fun close() {
        runCatching { output.flush() }
        descriptor.close()
    }
}
