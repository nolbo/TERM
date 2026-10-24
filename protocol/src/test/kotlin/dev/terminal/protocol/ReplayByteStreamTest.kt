package dev.terminal.protocol

import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ReplayByteStreamTest {
    @Test fun socketReplacementKeepsApplicationStreamAndUnconsumedPrefixExactlyOnce() {
        val left = ReplayByteStream(64); val right = ReplayByteStream(64)
        try {
            var link = connect(left, right)
            left.output.write("first-second".toByteArray())
            assertEquals("first", read(right, 5))
            link.close()
            left.output.write("-third".toByteArray())
            link = connect(left, right)
            assertEquals("-second-third", read(right, 13))
            right.output.write("response".toByteArray())
            assertEquals("response", read(left, 8))
            link.close()
        } finally { left.close(); right.close() }
    }

    @Test fun kotlinAndGuestPythonUseTheSameReplayWireAcrossReconnect() {
        val script = generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
            .map { it.resolve("image/scripts/private-share-transport.py") }.first { it.toFile().isFile }
        ServerSocket(0, 4, java.net.InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 5_000
            val process = ProcessBuilder("python3", "-c", """
                import importlib.util, socket, sys
                spec=importlib.util.spec_from_file_location('transport',sys.argv[1])
                module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
                relay=module.ReplayStream(64)
                for count in (3,6):
                    link=relay.attach(socket.create_connection(('127.0.0.1',int(sys.argv[2]))))
                    data=bytearray()
                    while len(data)<count:data.extend(relay.read(count-len(data)))
                    relay.write(data)
                    if count==3:assert link.wait(5)
                with relay.condition:assert relay.condition.wait_for(lambda:relay.acknowledged==relay.produced,5)
                relay.close()
                print('GUEST_REPLAY_OK')
            """.trimIndent(), script.toString(), server.localPort.toString()).redirectErrorStream(true).start()
            val relay = ReplayByteStream(64)
            try {
                var socket = server.accept()
                val first = relay.attach(socket.getInputStream(), socket.getOutputStream()) { socket.close() }
                relay.output.write("abc".toByteArray()); assertEquals("abc", read(relay, 3))
                first.close()
                relay.output.write("abcdef".toByteArray())
                socket = server.accept()
                relay.attach(socket.getInputStream(), socket.getOutputStream()) { socket.close() }
                assertEquals("abcdef", read(relay, 6))
                assertTrue(process.waitFor(5, TimeUnit.SECONDS))
                assertEquals(process.inputStream.bufferedReader().readText(), 0, process.exitValue())
            } finally { relay.close(); process.destroyForcibly() }
        }
    }

    private fun read(stream: ReplayByteStream, count: Int): String =
        ByteArray(count).also { DataInputStream(stream.input).readFully(it) }.decodeToString()

    private fun connect(left: ReplayByteStream, right: ReplayByteStream): ReplayByteStream.Link {
        val workers = Executors.newSingleThreadExecutor()
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            val client = Socket(java.net.InetAddress.getLoopbackAddress(), server.localPort)
            val accepted = server.accept()
            try {
                val other = workers.submit<ReplayByteStream.Link> {
                    right.attach(accepted.getInputStream(), accepted.getOutputStream()) { accepted.close() }
                }
                val link = left.attach(client.getInputStream(), client.getOutputStream()) { client.close() }
                other.get(3, TimeUnit.SECONDS)
                return link
            } finally { workers.shutdownNow() }
        }
    }
}
