package dev.terminal.runtime.avf.system

import java.io.Closeable
import java.io.IOException
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class PrivateShareOwnersTest {
    @Test fun anotherDriverCannotReleaseProtectionWhileOriginalIoHasNotStopped() {
        val key = UUID.randomUUID().toString()
        var stopped = false
        var closes = 0
        val owner = Closeable { closes++; if (!stopped) throw IOException("I/O still active") }
        PrivateShareOwners.register(key, owner)
        try {
            assertTrue(runCatching { PrivateShareOwners.releaseStopped(key) }.isFailure)
            assertTrue(runCatching { PrivateShareOwners.register(key, Closeable {}) }.isFailure)
            assertEquals(1, closes)
            stopped = true
            PrivateShareOwners.releaseStopped(key)
            assertEquals(2, closes)
            PrivateShareOwners.releaseStopped(key)
            assertEquals(2, closes)
            PrivateShareOwners.register(key, Closeable { closes++ })
            PrivateShareOwners.releaseStopped(key)
            assertEquals(3, closes)
        } finally { stopped = true; PrivateShareOwners.releaseStopped(key) }
    }

    @Test fun stoppingOneVmDoesNotCloseAnotherVmsIo() {
        val first = UUID.randomUUID().toString(); val second = UUID.randomUUID().toString()
        var firstClosed = false; var secondClosed = false
        PrivateShareOwners.register(first, Closeable { firstClosed = true })
        PrivateShareOwners.register(second, Closeable { secondClosed = true })
        try {
            PrivateShareOwners.releaseStopped(first)
            assertTrue(firstClosed); assertFalse(secondClosed)
        } finally { PrivateShareOwners.releaseStopped(first); PrivateShareOwners.releaseStopped(second) }
        assertTrue(secondClosed)
    }
}
