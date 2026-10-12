package dev.terminal.runtime.usb

import java.util.concurrent.atomic.AtomicReference

/** A canceled operation still owns the slot until native processes are reaped. */
internal class TrustedIphoneToolSlot<T : Any> {
    private val value = AtomicReference<T?>()
    fun get(): T? = value.get()
    fun compareAndSet(expected: T?, next: T?): Boolean = value.compareAndSet(expected, next)
}
