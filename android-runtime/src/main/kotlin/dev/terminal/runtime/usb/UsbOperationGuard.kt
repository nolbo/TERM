package dev.terminal.runtime.usb

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Authorization is checked around I/O, not held as a global monitor during I/O. */
internal class UsbOperationGuard(private val scheduler: ScheduledExecutorService, private val timeoutMs: Long = 5000) {
    fun <T> run(access: () -> Unit, cancel: () -> Unit, io: () -> T): T {
        val expired = AtomicBoolean()
        val deadline = scheduler.schedule({
            expired.set(true)
            cancel()
        }, timeoutMs, TimeUnit.MILLISECONDS)
        try {
            access()
            check(!expired.get()) { "USB_OPERATION_TIMED_OUT" }
            val result = io()
            check(!expired.get()) { "USB_OPERATION_TIMED_OUT_DELIVERY_UNCERTAIN" }
            access()
            return result
        } finally { deadline.cancel(false) }
    }
}
