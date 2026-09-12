package dev.terminal.runtime.usb

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RawRequestTest {
    @Test fun explicitConfigurationAndAlternateSelectionIsBounded() {
        val request = value("raw-open").put("device", "test").put("interface", 4).put("configuration", 2).put("alternate", 3)
        RawRequest.validate(request)
        for (key in listOf("interface", "configuration", "alternate")) {
            val previous = request.getInt(key)
            request.put(key, 256)
            assertTrue(runCatching { RawRequest.validate(request) }.isFailure)
            request.put(key, previous)
        }
        val poll = value("poll").put("endpoint", 129).put("length", 64).put("timeout", 100)
        RawRequest.validate(poll)
        poll.put("timeout", 0)
        assertTrue(runCatching { RawRequest.validate(poll) }.isFailure)
    }
    private fun value(op: String) = JSONObject().put("op", op).put("version", 1).put("token", "test")
    @Test fun inputAndOutputSchemasAreDistinct() {
        val request = value("transfer").put("endpoint", 129).put("length", 64).put("timeout", 100)
        RawRequest.validate(request)
        request.put("data", "00")
        assertTrue(runCatching { RawRequest.validate(request) }.isFailure)
        request.remove("length"); request.put("endpoint", 1)
        RawRequest.validate(request)
    }
    @Test fun noImplicitPermissionsCoercedFlagsOrUnknownFields() {
        val open = value("open").put("device", "test").put("interface", 0).put("serial", false)
        RawRequest.validate(open)
        open.put("force", true)
        assertTrue(runCatching { RawRequest.validate(open) }.isFailure)
        open.remove("force"); open.put("serial", "true")
        assertTrue(runCatching { RawRequest.validate(open) }.isFailure)
        assertTrue(runCatching { RawRequest.validate(value("reset")) }.isFailure)
    }
}
