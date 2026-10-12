package dev.terminal.runtime.usb

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class TrustedIphoneToolSlotTest {
    @Test fun concurrentDuplicateRejectedAndOnlyOwnerCanReleaseAfterCleanup() {
        val slot = TrustedIphoneToolSlot<Any>()
        val start = CountDownLatch(1)
        val won = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val attempts = (1..32).map { pool.submit { start.await(); if (slot.compareAndSet(null, Any())) won.incrementAndGet() } }
            start.countDown(); attempts.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, won.get())
            val owner = slot.get()!!
            assertFalse(slot.compareAndSet(null, Any())) // Cancellation has not yet reaped workers.
            assertFalse(slot.compareAndSet(Any(), null)) // Other callers cannot release.
            assertTrue(slot.compareAndSet(owner, null))
            assertTrue(slot.compareAndSet(null, Any()))
        } finally { pool.shutdownNow() }
    }
}
