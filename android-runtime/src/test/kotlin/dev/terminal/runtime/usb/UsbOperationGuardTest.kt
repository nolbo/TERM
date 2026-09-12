package dev.terminal.runtime.usb

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class UsbOperationGuardTest {
    @Test fun serialSharingRequiresExactVendorPortIndexMapping() {
        assertEquals(1, UsbAccessScope.isolatedPort("cp21xx", 1, listOf(0, 1), listOf(0, 0), 2, 1))
        assertEquals(0, UsbAccessScope.isolatedPort("ftdi", 1, listOf(0, 1), listOf(0, 0), 2, 0))
        assertEquals(-1, UsbAccessScope.isolatedPort("cp21xx", 1, listOf(3, 5), listOf(0, 0), 2, 1))
        assertEquals(-1, UsbAccessScope.isolatedPort("ftdi", 2, listOf(0, 1), listOf(0, 0), 2, 1))
        assertEquals(-1, UsbAccessScope.isolatedPort("ftdi", 1, listOf(0, 1), listOf(0, 1), 2, 1))
        assertEquals(-1, UsbAccessScope.isolatedPort("ch34x", 1, listOf(0, 1), listOf(0, 0), 2, 1))
    }
    @Test fun blockedDeviceDoesNotHoldAuthorizationMonitorAndRevocationSuppressesResult() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val worker = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val monitor = Any()
        var granted = true
        try {
            val result = worker.submit<Boolean> {
                runCatching { UsbOperationGuard(scheduler).run(
                    { synchronized(monitor) { check(granted) { "GRANT_REVOKED" } } }, { released.countDown() }) {
                    entered.countDown(); check(released.await(2, TimeUnit.SECONDS)); "sensitive result"
                } }.isFailure
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            synchronized(monitor) { granted = false }
            // An unrelated authorized device can finish while the first transport is stalled.
            assertEquals(7, UsbOperationGuard(scheduler).run({ synchronized(monitor) { Unit } }, {}) { 7 })
            released.countDown()
            assertTrue(result.get(2, TimeUnit.SECONDS))
        } finally { released.countDown(); worker.shutdownNow(); scheduler.shutdownNow() }
    }
    @Test fun deadlineClosesStalledTransportAndNeverReportsLateSuccess() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val cancelled = CountDownLatch(1)
        try {
            val result = runCatching { UsbOperationGuard(scheduler, 30).run({}, { cancelled.countDown() }) {
                check(cancelled.await(2, TimeUnit.SECONDS)); "late success"
            } }
            assertTrue(result.exceptionOrNull()?.message?.contains("DELIVERY_UNCERTAIN") == true)
        } finally { scheduler.shutdownNow() }
    }
    @Test fun deniedOperationNeverTouchesDeviceAndCompletingCancelsDeadline() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val touched = AtomicBoolean()
        try {
            assertTrue(runCatching { UsbOperationGuard(scheduler).run({ error("DENIED") }, {}) { touched.set(true) } }.isFailure)
            assertFalse(touched.get())
            assertEquals(1, UsbOperationGuard(scheduler).run({}, { touched.set(true) }) { 1 })
            assertFalse(touched.get())
        } finally { scheduler.shutdownNow() }
    }
    @Test fun interfaceReservationsAllowSeparatePortsButNotDeviceWideOrOtherGenerations() {
        val first = UsbAccessScope("generation1", 0, false)
        assertFalse(first.conflicts(UsbAccessScope("generation1", 1, false)))
        assertTrue(first.conflicts(UsbAccessScope("generation1", 0, false)))
        assertTrue(first.conflicts(UsbAccessScope("generation1", -1, true)))
        assertFalse(first.conflicts(UsbAccessScope("generation2", 0, false)))
    }
}
