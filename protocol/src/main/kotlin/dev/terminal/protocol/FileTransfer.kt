package dev.terminal.protocol

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** TFT1 is a separate host-CID-only guest-agent stream, independent of PTY/bridge framing. */
object FileTransfer {
    const val MAGIC = 0x54465431
    const val MAX_BYTES = 64L * 1024 * 1024
    const val TO_VM = 1
    const val FROM_VM = 2
    fun request(out: DataOutputStream, operation: Int, path: String) {
        require(operation == TO_VM || operation == FROM_VM)
        val bytes = path.toByteArray(Charsets.UTF_8)
        val parts = path.split('/')
        require(bytes.size in 1..4096 && !path.startsWith('/') && parts.size <= 65 && parts.all { it.isNotEmpty() && it != "." && it != ".." && it.toByteArray().size <= 255 } && path.none(Char::isISOControl))
        out.writeInt(MAGIC); out.writeByte(operation); out.writeShort(bytes.size); out.write(bytes); out.flush()
    }
    fun checkGreeting(input: DataInputStream) { require(input.readInt() == MAGIC) { "Guest file-transfer agent is unavailable or incompatible" } }
    fun checkStatus(input: DataInputStream) { require(input.readUnsignedByte() == 0) { "Guest rejected the file transfer (path, permissions, size or existing destination)" } }
    /** Exactly size bytes, bounded memory and an end-to-end SHA-256 trailer. */
    fun copy(input: InputStream, output: OutputStream, size: Long, check: () -> Unit = {}, progress: (Long) -> Unit = {}): ByteArray {
        require(size in 0..MAX_BYTES) { "File exceeds the 64 MiB transfer limit" }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        while (copied < size) {
            check()
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), size - copied).toInt())
            require(count > 0) { "Truncated transfer" }
            digest.update(buffer, 0, count); output.write(buffer, 0, count)
            copied += count; progress(copied)
        }
        check()
        return digest.digest()
    }
    fun verify(expected: ByteArray, actual: ByteArray) { require(expected.size == 32 && MessageDigest.isEqual(expected, actual)) { "SHA-256 verification failed" } }
}
