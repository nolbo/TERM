package dev.terminal.runtime.lifecycle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderedSessionInputTest {
    @Test fun `suspending first write cannot be overtaken by following input`() = runBlocking {
        val worker = CoroutineScope(coroutineContext + SupervisorJob())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Unit>()
        val received = mutableListOf<String>()
        val input = OrderedSessionInput(worker) { _, bytes ->
            if (received.isEmpty()) {
                entered.complete(Unit)
                release.await()
            }
            received += bytes.decodeToString()
            if (received.size == 3) done.complete(Unit)
        }
        try {
            assertTrue(input.offer("native", "echo ".toByteArray()))
            withTimeout(5000) { entered.await() }
            val text = "one\\\rtwo".toByteArray()
            assertTrue(input.offer("native", text))
            text.fill(0)
            assertTrue(input.offer("native", "\r".toByteArray()))
            assertTrue(received.isEmpty())
            release.complete(Unit)
            withTimeout(5000) { done.await() }
            assertEquals(listOf("echo ", "one\\\rtwo", "\r"), received)
        } finally { worker.cancel() }
    }
}
