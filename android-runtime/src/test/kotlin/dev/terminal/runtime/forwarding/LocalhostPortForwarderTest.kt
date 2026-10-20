package dev.terminal.runtime.forwarding

import dev.terminal.runtime.api.GuestByteStream
import org.junit.Assert.*
import org.junit.Test
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class LocalhostPortForwarderTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private fun freePort() = ServerSocket(0, 16, loopback).use { it.localPort }
    @Test fun sharedListenerBudgetRejectsExcessButPermitsSafeReplacementAndReleasesSlots() {
        val budget = Semaphore(1)
        val first = LocalhostPortForwarder({ _, _ -> Result.failure(Exception()) }, listenerBudget = budget)
        val second = LocalhostPortForwarder({ _, _ -> Result.failure(Exception()) }, listenerBudget = budget)
        try {
            first.replace("one", 8000, "::1", freePort()).getOrThrow()
            assertEquals("SERVICE_LIMIT", second.replace("two", 8000, "::1", freePort()).exceptionOrNull()?.message)
            first.replace("one", 8000, "::1", freePort()).getOrThrow()
            first.stop("one")
            second.replace("two", 8000, "::1", freePort()).getOrThrow()
        } finally { first.close(); second.close() }
        assertEquals(1, budget.availablePermits())
    }
    @Test fun conflictAndFailedCommitKeepOldLoopbackListenerAndCloseNewCandidate() {
        val forwarder = LocalhostPortForwarder({ _, _ -> Result.failure(Exception()) })
        val oldPort = freePort()
        try {
            assertTrue(forwarder.replace("one", 8000, "127.0.0.1", oldPort).isSuccess)
            ServerSocket(0, 16, loopback).use { occupied ->
                assertTrue(forwarder.replace("one", 8000, "127.0.0.1", occupied.localPort).isFailure)
                assertTrue(forwarder.replace("two", 8000, "::1", oldPort).isFailure)
                assertFalse(occupied.isClosed)
            }
            val candidate = freePort()
            assertTrue(forwarder.replace("one", 8000, "127.0.0.1", candidate) { error("STORAGE_FAILED") }.isFailure)
            ServerSocket(candidate, 16, loopback).use { assertEquals(candidate, it.localPort) }
            assertTrue(runCatching { ServerSocket(oldPort, 16, loopback).close() }.isFailure)
        } finally { forwarder.close() }
        // Closing the socket wakes the accept thread; its final OS reference can outlive close().
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (true) {
            try { ServerSocket(oldPort, 16, loopback).use { }; break }
            catch (failure: BindException) {
                if (System.nanoTime() >= deadline) throw failure
                Thread.sleep(10)
            }
        }
    }
    @Test fun proxyUsesGuestTargetAndSupportsRequestHalfCloseAndResourceCleanup() {
        val guestServer = ServerSocket(0, 16, loopback)
        val executor = Executors.newSingleThreadExecutor()
        val target = mutableListOf<Pair<String, Int>>()
        val budget = Semaphore(1)
        val listenerBudget = Semaphore(1)
        val forwarder = LocalhostPortForwarder({ address, port ->
            target += address to port
            val socket = Socket(loopback, guestServer.localPort)
            Result.success(object : GuestByteStream {
                override val input = socket.getInputStream()
                override val output = socket.getOutputStream()
                override fun shutdownOutput() = socket.shutdownOutput()
                override fun close() = socket.close()
            })
        }, budget, listenerBudget)
        val served = executor.submit {
            guestServer.accept().use { peer ->
                peer.soTimeout = 3000
                assertEquals("request", peer.getInputStream().readBytes().decodeToString())
                peer.getOutputStream().write("response".toByteArray())
            }
        }
        val hostPort = freePort()
        try {
            forwarder.replace("one", 8000, "::1", hostPort).getOrThrow()
            Socket(loopback, hostPort).use { client ->
                client.soTimeout = 5000
                client.getOutputStream().write("request".toByteArray()); client.shutdownOutput()
                assertEquals("response", client.getInputStream().readBytes().decodeToString())
            }
            served.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("::1" to 8000), target)
        } finally { forwarder.close(); guestServer.close(); executor.shutdownNow() }
        assertTrue(budget.tryAcquire(5, TimeUnit.SECONDS))
        assertEquals(1, listenerBudget.availablePermits())
        // The accept thread can retain the listener's final OS reference after close().
        // Rebinding proves release without racing a connect against the closing listener.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (true) {
            try {
                ServerSocket().use {
                    it.reuseAddress = true
                    it.bind(InetSocketAddress(loopback, hostPort), 16)
                }
                break
            } catch (failure: BindException) {
                if (System.nanoTime() >= deadline) throw failure
                Thread.sleep(10)
            }
        }
    }
}
