package dev.terminal.runtime.session

import org.junit.Assert.*
import org.junit.Test

class SessionMemoryTest {
    @Test fun capturesIdentityAfterTemporaryPreExecProcRestriction() {
        val expected = ProcessMemoryIdentity(42, 12345)
        var reads = 0
        var pauses = 0
        val identity = awaitProcessMemoryIdentity(42, readIdentity = { pid ->
            assertEquals(expected.pid, pid)
            if (++reads < 3) null else expected
        }, pause = { pauses++ })
        assertEquals(expected, identity)
        assertEquals(3, reads)
        assertEquals(2, pauses)
    }

    @Test fun readableIdentityDoesNotDelaySessionStartup() {
        val expected = ProcessMemoryIdentity(42, 12345)
        var reads = 0
        assertEquals(expected, awaitProcessMemoryIdentity(42, readIdentity = {
            reads++
            expected
        }, pause = { fail("Readable process must not wait") }))
        assertEquals(1, reads)
    }

    @Test fun permanentProcRestrictionRemainsUnavailableWithBoundedRetries() {
        var reads = 0
        var pauses = 0
        assertNull(awaitProcessMemoryIdentity(42, readIdentity = {
            reads++
            null
        }, pause = { pauses++ }))
        assertEquals(100, reads)
        assertEquals(99, pauses)
    }

    @Test fun parsesNamesContainingSpacesAndClosingParentheses() {
        val fields = MutableList(22) { "0" }.apply {
            this[0] = "S"; this[1] = "7"; this[3] = "42"; this[19] = "12345"; this[21] = "16"
        }
        val parsed = parseProcessMemoryStat("42 (a tricky ) name) " + fields.joinToString(" "))!!
        assertEquals(ProcessMemoryIdentity(42, 12345), parsed.identity)
        assertEquals(7, parsed.parent)
        assertEquals(42, parsed.linuxSession)
        assertEquals(16L, parsed.residentPages)
        assertNull(parseProcessMemoryStat("broken"))
    }

    @Test fun includesChildrenAndSessionOrphansButRejectsReusedRootPid() {
        fun stat(pid: Int, parent: Int, session: Int, ticks: Long = 9) =
            ProcessMemoryStat(ProcessMemoryIdentity(pid, ticks), parent, session, 10)
        val root = ProcessMemoryIdentity(42, 9)
        val processes = listOf(stat(42, 1, 42), stat(43, 42, 42), stat(44, 43, 44),
            stat(45, 1, 42), stat(46, 1, 46))
        assertEquals(4 * 10 * 4096L, sessionResidentBytes(root, processes, 4096))
        assertEquals(4 * 10 * 16384L, sessionResidentBytes(root, processes, 16384))
        assertNull(sessionResidentBytes(root.copy(startTicks = 1), processes, 4096))
        assertNull(sessionResidentBytes(root, emptyList(), 4096))
    }
}
