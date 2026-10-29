package dev.terminal.runtime.vsock

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in native socket probe; excludes vsock, VM, terminal parsing and rendering. */
class FramePrefetchBenchmarkDeviceTest {
    @Test fun comparePrefetchCapacities() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("framePrefetchBenchmark") == "true")
        repeat(2) { for (capacity in listOf(64, 0, 1, 8)) { echo(capacity); bulk(capacity) } }
        for (round in 0..4) {
            val capacities = listOf(64, 0, 1, 8).let { if (round % 2 == 0) it else it.reversed() }
            for (capacity in capacities) {
                val echo = echo(capacity)
                val bulk = bulk(capacity)
                Log.i("AvfPrefetchBench", "round=$round capacity=$capacity p50_us=${echo[echo.size / 2]} p95_us=${echo[echo.size * 95 / 100]} bulk_mib_s=$bulk")
            }
        }
    }

    // Same native streams/codec/flow boundary as production, with only channel capacity varied.
    private fun frames(input: DataInputStream, capacity: Int): Flow<ProtocolFrame> = channelFlow {
        try { while (isActive) send(Protocol.read(input)) } catch (_: EOFException) { }
        catch (failure: IOException) { currentCoroutineContext().ensureActive(); throw failure }
    }.buffer(capacity).flowOn(Dispatchers.IO)

    private suspend fun echo(capacity: Int): List<Double> = coroutineScope {
        val sockets = ParcelFileDescriptor.createSocketPair()
        val host = ParcelFileDescriptorByteStream(sockets[0])
        val peer = ParcelFileDescriptorByteStream(sockets[1])
        val input = DataInputStream(BufferedInputStream(host.input))
        val out = BufferedOutputStream(host.output)
        val responses = kotlinx.coroutines.channels.Channel<ProtocolFrame>()
        val reader = launch { frames(input, capacity).collect { responses.send(it) } }
        val frame = ProtocolFrame(service = Service.SESSION, kind = FrameKind.STREAM, requestId = 0, payload = byteArrayOf(7))
        val wire = Protocol.encode(frame)
        val remote = launch(Dispatchers.IO) {
            val source = DataInputStream(BufferedInputStream(peer.input))
            val sink = BufferedOutputStream(peer.output)
            repeat(1_200) { Protocol.read(source); sink.write(wire); sink.flush() }
        }
        try { withTimeout(20_000) {
            val times = mutableListOf<Double>()
            repeat(1_200) { index ->
                val start = System.nanoTime()
                withContext(Dispatchers.IO) { out.write(wire); out.flush() }
                val received = responses.receive()
                assertEquals(frame.requestId, received.requestId)
                assertArrayEquals(frame.payload, received.payload)
                if (index >= 200) times += (System.nanoTime() - start) / 1_000.0
            }
            remote.join()
            times.sorted()
        } } finally { reader.cancel(); remote.cancel(); host.close(); peer.close(); reader.join(); remote.join() }
    }

    private suspend fun bulk(capacity: Int): Double = coroutineScope {
        val sockets = ParcelFileDescriptor.createSocketPair()
        val host = ParcelFileDescriptorByteStream(sockets[0])
        val peer = ParcelFileDescriptorByteStream(sockets[1])
        val frame = ProtocolFrame(service = Service.SESSION, kind = FrameKind.STREAM, requestId = 0, payload = ByteArray(8192) { 7 })
        val wire = Protocol.encode(frame)
        val start = System.nanoTime()
        val writer = launch(Dispatchers.IO) { repeat(2_048) { peer.output.write(wire) }; peer.shutdownOutput() }
        try { withTimeout(20_000) {
            var bytes = 0
            frames(DataInputStream(BufferedInputStream(host.input)), capacity).collect {
                assertEquals(8192, it.payload.size); assertEquals(7, it.payload[0].toInt()); bytes += it.payload.size
            }
            writer.join()
            assertEquals(16 * 1024 * 1024, bytes)
            16.0 * 1e9 / (System.nanoTime() - start)
        } } finally { host.close(); peer.close(); writer.cancelAndJoin() }
    }
}
