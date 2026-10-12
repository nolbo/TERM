package dev.terminal.runtime.usb

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class HostToolProcessCleanupTest {
    private class Child : java.lang.Process() {
        var live = true; var kills = 0; var waits = 0
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun destroy() { kills++ }
        override fun destroyForcibly(): java.lang.Process { kills++; return this }
        override fun isAlive() = live
        override fun exitValue(): Int { if (live) throw IllegalThreadStateException(); return 0 }
        override fun waitFor(): Int { live = false; return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            waits++; if (waits == 1) throw InterruptedException()
            if (waits == 3) live = false
            return !live
        }
    }
    @Test fun interruptedCleanupStillReapsAllChildrenBeforeReturningAndRestoresInterrupt() {
        val children = listOf(Child(), Child())
        try {
            HostToolProcessCleanup.reap(children)
            assertTrue(Thread.currentThread().isInterrupted)
            children.forEach { assertFalse(it.isAlive); assertEquals(3, it.kills); assertEquals(3, it.waits) }
        } finally { Thread.interrupted() }
    }
}
