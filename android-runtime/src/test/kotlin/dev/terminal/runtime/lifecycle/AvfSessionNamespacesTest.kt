package dev.terminal.runtime.lifecycle

import dev.terminal.runtime.session.Environment
import dev.terminal.runtime.session.SessionRuntime
import org.junit.Assert.*
import org.junit.Test

class AvfSessionNamespacesTest {
    @Test fun serviceRestartKeepsIdentityUntilVerifiedStopAndDoesNotChangeSibling() {
        val values = mutableMapOf<String, String>()
        fun owner() = AvfSessionNamespaces(values::get) { key, value ->
            if (value == null) values.remove(key) else values[key] = value
        }
        val environment = Environment.create(SessionRuntime.AVF, "one").id
        val sibling = Environment.create(SessionRuntime.AVF, "two").id
        val first = owner().get(environment)
        val second = owner().get(sibling)
        val restarted = owner()
        assertEquals(first, restarted.get(environment))
        restarted.retire(environment)
        assertNotEquals(first, owner().get(environment))
        assertEquals(second, owner().get(sibling))
    }

    @Test fun failedPersistenceCannotExposeAnUnrecoverableIdentity() {
        val environment = Environment.create(SessionRuntime.AVF, "one").id
        val namespaces = AvfSessionNamespaces({ null }) { _, _ -> error("disk failure") }
        assertThrows(IllegalStateException::class.java) { namespaces.get(environment) }
    }
}
