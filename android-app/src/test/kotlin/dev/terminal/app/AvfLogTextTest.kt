package dev.terminal.app

import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class AvfLogTextTest {
    @Test fun legacyRecordsBecomeOneChronologicalTextWithPreciseBracketedLocalTimes() {
        assertEquals("[1970-01-01 09:00:00.042 +09:00] event=vm.start\n" +
            "[1970-01-01 09:00:01.000 +09:00] event=session.connected\n",
            avfLogText(listOf("time_ms=42 event=vm.start", "time_ms=1000 event=session.connected"), zone = ZoneId.of("Asia/Seoul")))
    }
    @Test fun searchMatchesDisplayedTimeAndMetadataWhileMalformedHistoryRemainsReadable() {
        val entries = listOf("time_ms=42 event=vm.start", "time_ms=invalid event=vm.failed", "legacy-record")
        assertEquals("[1970-01-01 00:00:00.042 Z] event=vm.start\n", avfLogText(entries, "00:00:00.042", ZoneId.of("UTC")))
        assertEquals("[unknown] time_ms=invalid event=vm.failed\n", avfLogText(entries, "VM.FAILED"))
        assertTrue(avfLogText(entries).endsWith("[unknown] legacy-record\n"))
        assertEquals("", avfLogText(entries, "no-match"))
        assertEquals("", avfLogText(emptyList()))
    }
}
