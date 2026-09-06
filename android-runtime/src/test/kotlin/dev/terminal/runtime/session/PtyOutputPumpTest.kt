package dev.terminal.runtime.session

import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PtyOutputPumpTest {
    @Test fun closeInterruptedReadIsContainedAfterAlreadyReadOutputIsDelivered() = runBlocking {
        val interruption = InterruptedIOException("read interrupted by close() on another thread")
        val received = mutableListOf<ByteArray>()
        val failures = mutableListOf<IOException>()
        val input = object : InputStream() {
            private var firstRead = true

            override fun read(): Int = error("bulk reads only")

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (!firstRead) throw interruption
                firstRead = false
                val bytes = "before-close".encodeToByteArray()
                bytes.copyInto(buffer, offset)
                return bytes.size
            }
        }

        pumpPtyOutput(input, received::add, failures::add)

        assertArrayEquals("before-close".encodeToByteArray(), received.single())
        assertSame(interruption, failures.single())
    }
}
