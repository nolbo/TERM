package dev.terminal.runtime.backup

import java.io.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Bounded authenticated frames avoid providers buffering a whole GCM ciphertext until doFinal.
 * The authenticated empty terminal frame detects truncation at a valid chunk boundary.
 * Sequence, length and the entire fixed header are AAD. Every frame uses a distinct nonce.
 */
internal object BackupTransport {
    private val magic = "TERMBACKUP\r\n".toByteArray(Charsets.US_ASCII)
    private const val ITERATIONS = 210_000
    private const val FRAME = BackupLimits.BUFFER

    private class Header(val encoded: ByteArray, val key: ByteArray?, val nonce: ByteArray) {
        fun transform(mode: Int, index: Int, size: Int, data: ByteArray): ByteArray {
            val secret = key ?: return data
            val iv = ByteBuffer.allocate(12).put(nonce).putInt(index).array()
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(mode, SecretKeySpec(secret, "AES"), GCMParameterSpec(128, iv))
                updateAAD(encoded)
                updateAAD(ByteBuffer.allocate(8).putInt(index).putInt(size).array())
                doFinal(data)
            }
        }
        fun clear() { key?.fill(0) }
    }

    private fun key(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    private fun createHeader(password: CharArray): Header {
        require(password.size <= 1024) { "Password is too long" }
        val encrypted = password.isNotEmpty()
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val bytes = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).apply {
            write(magic); writeInt(1); writeByte(if (encrypted) 1 else 0); write(salt); write(nonce)
        } }.toByteArray()
        return Header(bytes, if (encrypted) key(password, salt) else null, nonce)
    }
    private fun readHeader(input: DataInputStream, password: CharArray): Header {
        require(password.size <= 1024)
        val bytes = ByteArray(magic.size + 4 + 1 + 16 + 8).also(input::readFully)
        val data = DataInputStream(ByteArrayInputStream(bytes))
        val actual = ByteArray(magic.size).also(data::readFully)
        require(actual.contentEquals(magic) && data.readInt() == 1) { "Unsupported or damaged .termbackup file" }
        val flags = data.readUnsignedByte()
        require(flags in 0..1) { "Unsupported backup encryption" }
        val salt = ByteArray(16).also(data::readFully)
        val nonce = ByteArray(8).also(data::readFully)
        require(flags == 0 || password.isNotEmpty()) { "A password is required for this backup" }
        return Header(bytes, if (flags == 1) key(password, salt) else null, nonce)
    }

    class Writer internal constructor(output: OutputStream, password: CharArray) : OutputStream() {
        private val out = DataOutputStream(output)
        private val header = createHeader(password)
        val fingerprint: ByteArray = MessageDigest.getInstance("SHA-256").digest(header.encoded)
        private val buffer = ByteArray(FRAME)
        private var used = 0
        private var sequence = 0
        private var finished = false
        init { out.write(header.encoded) }
        override fun write(value: Int) { buffer[used++] = value.toByte(); if (used == FRAME) frame() }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            var at = offset
            var remaining = length
            while (remaining > 0) {
                val count = minOf(remaining, FRAME - used)
                bytes.copyInto(buffer, used, at, at + count)
                used += count; at += count; remaining -= count
                if (used == FRAME) frame()
            }
        }
        private fun frame() {
            check(!finished && sequence < Int.MAX_VALUE)
            val payload = header.transform(Cipher.ENCRYPT_MODE, sequence++, used, buffer.copyOf(used))
            out.writeInt(used); out.write(payload)
            used = 0
        }
        fun finish() {
            if (finished) return
            if (used > 0) frame()
            frame() // An empty, authenticated END frame.
            finished = true
            header.clear(); buffer.fill(0); out.flush()
        }
        override fun close() { header.clear(); buffer.fill(0) }
    }

    class Reader internal constructor(input: InputStream, password: CharArray) : InputStream() {
        private val source = DataInputStream(input)
        private val header = readHeader(source, password)
        val fingerprint: ByteArray = MessageDigest.getInstance("SHA-256").digest(header.encoded)
        private var buffer = ByteArray(0)
        private var at = 0
        private var sequence = 0
        private var ended = false
        private fun next(): Boolean {
            if (ended) return false
            require(sequence < Int.MAX_VALUE) { "Too many encrypted frames" }
            val size = source.readInt()
            require(size in 0..FRAME) { "Invalid backup frame size" }
            val payload = ByteArray(size + if (header.key != null) 16 else 0).also(source::readFully)
            buffer.fill(0)
            buffer = try { header.transform(Cipher.DECRYPT_MODE, sequence++, size, payload) }
            catch (failure: java.security.GeneralSecurityException) {
                throw IOException("Incorrect password or damaged authenticated backup", failure)
            }
            at = 0
            if (size == 0) {
                require(source.read() == -1) { "Unexpected data after backup END frame" }
                ended = true
                header.clear()
            }
            return !ended
        }
        override fun read(): Int {
            if (at == buffer.size && !next()) return -1
            return buffer[at++].toInt() and 255
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (at == buffer.size && !next()) return -1
            val count = minOf(length, buffer.size - at)
            buffer.copyInto(bytes, offset, at, at + count); at += count
            return count
        }
        /** Gzip can stop before the final transport frame. Require its authenticated END too. */
        fun verifyEnd() { require(read() == -1) { "Trailing compressed backup data" } }
        override fun close() { header.clear(); buffer.fill(0) }
    }
}
