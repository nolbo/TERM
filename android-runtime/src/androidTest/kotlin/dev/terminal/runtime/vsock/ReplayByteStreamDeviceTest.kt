package dev.terminal.runtime.vsock

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.terminal.protocol.ReplayByteStream
import java.io.DataInputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReplayByteStreamDeviceTest {
    @Test(timeout = 15_000) fun nativeSocketBatchesKeepUnconsumedDataAcrossReplacementAndDuplexCredit() {
        val left = ReplayByteStream()
        val right = ReplayByteStream()
        val workers = Executors.newFixedThreadPool(2)
        val streams = mutableListOf<ParcelFileDescriptorByteStream>()
        fun connect(): List<ReplayByteStream.Link> {
            val sockets = ParcelFileDescriptor.createSocketPair().map { ParcelFileDescriptorByteStream(it) }
            streams.addAll(sockets)
            val links = listOf(left, right).zip(sockets).map { (owner, stream) ->
                workers.submit<ReplayByteStream.Link> { owner.attach(stream.input, stream.output) { stream.close() } }
            }
            return links.map { it.get(3, TimeUnit.SECONDS) }
        }
        fun read(owner: ReplayByteStream, count: Int) = ByteArray(count).also { DataInputStream(owner.input).readFully(it) }
        try {
            val prefix = ByteArray(4 * ReplayByteStream.CHUNK) { (it * 17).toByte() }
            left.output.write(prefix)
            val first = connect()
            assertArrayEquals(prefix.copyOfRange(0, 7), read(right, 7))
            first.forEach { it.close() }
            connect()
            assertArrayEquals(prefix.copyOfRange(7, prefix.size), read(right, prefix.size - 7))

            val a = ByteArray(ReplayByteStream.WINDOW * 2 + 137) { it.toByte() }
            val b = ByteArray(ReplayByteStream.WINDOW * 2 + 139) { (it * 31).toByte() }
            val sendingA = workers.submit { left.output.write(a) }
            val sendingB = workers.submit { right.output.write(b) }
            assertArrayEquals(a, read(right, a.size))
            assertArrayEquals(b, read(left, b.size))
            sendingA.get(3, TimeUnit.SECONDS); sendingB.get(3, TimeUnit.SECONDS)
        } finally {
            left.close(); right.close(); streams.forEach { it.close() }; workers.shutdownNow()
            workers.awaitTermination(2, TimeUnit.SECONDS)
        }
    }
}
