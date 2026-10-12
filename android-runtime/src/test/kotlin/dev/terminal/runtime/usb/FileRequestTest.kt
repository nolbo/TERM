package dev.terminal.runtime.usb

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FileRequestTest {
    private fun request(op: String) = JSONObject().put("op", op).put("version", 1).put("token", "session")
    @Test fun fileGrantDoesNotAcceptPathsUrisOrImplicitWritePermission() {
        val value = request("files-open").put("root", "opaque").put("mode", "read")
        FileRequest.validate(value)
        value.put("uri", "content://provider/private")
        assertTrue(runCatching { FileRequest.validate(value) }.isFailure)
        value.remove("uri"); value.put("mode", "read-write")
        assertTrue(runCatching { FileRequest.validate(value) }.isFailure)
        for (op in listOf("delete", "mount", "format", "rename")) assertTrue(runCatching { FileRequest.validate(request(op)) }.isFailure)
    }
    @Test fun uploadIsBoundedAndDoesNotAcceptTraversalOrCoercedSizes() {
        val value = request("put").put("parent", "root").put("name", "input.bin").put("size", 0)
        FileRequest.validate(value)
        for (size in listOf<Any>(-1, 67108865, "1", true, 1.5)) {
            value.put("size", size)
            assertTrue(runCatching { FileRequest.validate(value) }.isFailure)
        }
        value.put("size", 0).put("name", "../private")
        assertTrue(runCatching { FileRequest.validate(value) }.isFailure)
    }
}
