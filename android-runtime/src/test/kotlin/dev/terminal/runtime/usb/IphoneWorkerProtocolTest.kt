package dev.terminal.runtime.usb

import java.io.ByteArrayOutputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class IphoneWorkerProtocolTest {
    private fun run(lines: String, action: String = "afc-get", size: Int = 0,
        output: ByteArrayOutputStream = ByteArrayOutputStream(), upload: (Int) -> ByteArray = { error("unexpected upload") },
        event: (JSONObject) -> Unit = {}): JSONObject = IphoneWorkerProtocol.exchange(
        (lines.trimIndent() + "\n").byteInputStream(), output, action, "/TerminalImports/test", size, event, upload)

    @Test fun binaryDownloadAndEmptyFile() {
        val events = mutableListOf<String>()
        val result = run("""
            {"event":"start","size":2}
            {"event":"data","offset":0,"data":"00ff"}
            {"ok":true,"result":{"bytes":2,"completed":true}}
        """, event = { events += it.getString("event") })
        assertEquals(2, result.getInt("bytes"))
        assertEquals(listOf("start", "data"), events)
        assertEquals(0, run("""
            {"event":"start","size":0}
            {"ok":true,"result":{"bytes":0,"completed":true}}
        """).getInt("bytes"))
    }

    @Test fun invalidStreamsFailClosed() {
        for (body in listOf(
            """{"event":"start","size":67108865}""",
            """{"event":"data","offset":0,"data":"00"}""",
            """{"event":"start","size":1}
{"event":"data","offset":1,"data":"00"}""",
            """{"event":"start","size":1}
{"ok":true,"result":{"bytes":1,"completed":true}}""",
            """{"event":"start","size":0}
{"ok":true,"result":{"bytes":0,"completed":true,"HostPrivateKey":"secret"}}""",
            """{"event":"unknown"}"""
        )) assertTrue(body, runCatching { run(body) }.isFailure)
    }

    @Test fun uploadIsChunkedAndAcknowledged() {
        val output = ByteArrayOutputStream()
        val events = mutableListOf<JSONObject>()
        run("""
            {"event":"ready"}
            {"ok":true,"result":{"bytes":3,"completed":true}}
        """, "afc-put", 3, output, { offset -> if (offset == 0) byteArrayOf(0, -1) else byteArrayOf(1) }, { events += it })
        assertArrayEquals(byteArrayOf(0, -1, 1), output.toByteArray())
        assertEquals(listOf("ready", "accepted", "accepted"), events.map { it.getString("event") })
        assertEquals(3, events.last().getInt("offset"))
    }

    @Test fun pathsAndNamesAreBounded() {
        for (path in listOf("/", "/DCIM/100APPLE/a.jpg")) assertEquals(path, IphonePolicy.path(path))
        for (path in listOf("relative", "/a/../b", "/a//b", "/a/", "/a\\b", "/a\n"))
            assertTrue(path, runCatching { IphonePolicy.path(path) }.isFailure)
        for (name in listOf("../x", "/x", "a b", "")) assertTrue(runCatching { IphonePolicy.uploadName(name) }.isFailure)
        assertTrue(runCatching { IphonePolicy.size(1L) }.isFailure)
        assertTrue(runCatching { IphonePolicy.hex("AA") }.isFailure)
    }
}
