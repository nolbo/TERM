package dev.terminal.runtime.usb

import java.util.concurrent.TimeUnit

/** A canceled/interrupted runner cannot release a USB lease while a child still lives. */
internal object HostToolProcessCleanup {
    fun reap(children: List<java.lang.Process>) {
        var interrupted = false
        try {
            children.forEach { child ->
                while (child.isAlive) {
                    child.destroyForcibly()
                    try { child.waitFor(1, TimeUnit.SECONDS) }
                    catch (_: InterruptedException) { interrupted = true }
                }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt() }
    }
}
