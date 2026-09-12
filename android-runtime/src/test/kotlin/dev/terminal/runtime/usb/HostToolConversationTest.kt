package dev.terminal.runtime.usb

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HostToolConversationTest {
    @Test fun pairingStagesKeepSafeErrorDetailAndCancelAtEachBoundary() {
        val steps = listOf("PAIRING", "CHECKING_PAIR_RECORD", "WAITING_FOR_TRUST", "SAVING_PAIR_RECORD", "VALIDATING_PAIRING", "READING_DEVICE_INFORMATION")
        for (index in steps.indices) for (code in listOf("CANCELED", "USB_DETACHED")) {
            val record = record()
            steps.take(index + 1).forEach { phase(record, it) }
            record.fail(code)
            assertEquals(code, record.snapshot().getString("error"))
            assertEquals(steps[index], record.snapshot().getString("phase"))
        }
        for (detail in listOf("IO_EOF", "credential-sentinel")) {
            val record = record(); steps.take(4).forEach { phase(record, it) }
            record.event(JSONObject().put("phase", "FAILED").put("error", "PAIR_RECORD_SAVE_FAILED").put("detail", detail))
            val snapshot = record.snapshot()
            assertEquals("SAVING_PAIR_RECORD", snapshot.getString("phase"))
            assertEquals("PAIR_RECORD_SAVE_FAILED", snapshot.getString("error"))
            assertEquals(3, snapshot.getInt("serviceRevision"))
            assertEquals(detail == "IO_EOF", snapshot.has("detail"))
            assertFalse(snapshot.toString().contains("credential-sentinel"))
        }
        val reused = record()
        listOf("PAIRING", "CHECKING_PAIR_RECORD", "VALIDATING_PAIRING", "READING_DEVICE_INFORMATION").forEach { phase(reused, it) }
    }
    private fun record() = HostToolConversation("operation", "isideload", "attachment")
    @Test fun residualChangesSurviveFailureAndCancellationWithoutSecretMetadata() {
        for (error in listOf("CANCELED", "USB_DETACHED", "AFC_PLACEMENT")) {
            val record = record(); login(record)
            reply(record, arrayOf("account", "password", HostToolConversation.DEFAULT_ENDPOINT))
            for (step in listOf("APPLE_LOGIN", "REGISTERING_DEVICE", "DOWNLOADING_SIDESTORE", "SIGNING", "INSTALLING", "GENERATING_PAIRING")) phase(record, step)
            record.event(JSONObject().put("phase", "GENERATING_PAIRING").put("wifiDebugging", "UNCHANGED").put("secret", "sentinel"))
            phase(record, "PLACING_PAIRING"); record.fail(error)
            val snapshot = record.snapshot()
            assertEquals("CONFIRMED", snapshot.getJSONObject("changes").getString("appInstallation"))
            assertEquals("UNCHANGED", snapshot.getJSONObject("changes").getString("wifiDebugging"))
            assertEquals("POSSIBLE", snapshot.getJSONObject("changes").getString("pairingFile"))
            assertFalse(snapshot.toString().contains("sentinel"))
        }
        val record = record()
        assertTrue(runCatching { record.event(JSONObject().put("phase", "GENERATING_PAIRING").put("wifiDebugging", "CONFIRMED")) }.isFailure)
    }
    private fun phase(record: HostToolConversation, phase: String) { record.event(JSONObject().put("phase", phase)) }
    private fun login(record: HostToolConversation) {
        for (step in listOf("PAIRING", "WAITING_FOR_TRUST", "READING_DEVICE_INFORMATION", "WAITING_FOR_LOGIN")) phase(record, step)
    }
    private fun nonce(record: HostToolConversation) = record.snapshot(true).getJSONObject("prompt").getString("nonce")
    private fun reply(record: HostToolConversation, values: Array<String>) {
        record.submit(nonce(record), values, true); record.awaitReply().fill("")
    }
    private fun choices(record: HostToolConversation, phase: String) {
        record.event(JSONObject().put("phase", phase).put("choices", JSONArray()
            .put(JSONObject().put("id", "A").put("label", "First"))
            .put(JSONObject().put("id", "B").put("label", "Second"))))
    }
    @Test fun secretsNeverAppearInPublicOrHostSnapshotsAndCannotBeSubmittedFromGuest() {
        val record = record(); login(record)
        val token = nonce(record)
        assertFalse(record.snapshot().toString().contains(token))
        val values = arrayOf("account-sentinel", "password-sentinel", HostToolConversation.DEFAULT_ENDPOINT)
        assertTrue(runCatching { record.submit(null, values, false) }.isFailure)
        assertTrue(runCatching { record.submit("wrong", values, true) }.isFailure)
        record.submit(token, values, true)
        for (host in listOf(false, true)) {
            val text = record.snapshot(host).toString()
            assertFalse(text.contains(values[0])); assertFalse(text.contains(values[1]))
        }
        assertTrue(runCatching { record.submit(token, values, true) }.isFailure)
        assertArrayEquals(values, record.awaitReply())
    }
    @Test fun completeInteractiveFlowRequiresExplicitTeamAndCertificateDecisions() {
        val record = record(); login(record)
        reply(record, arrayOf("account", "password", HostToolConversation.DEFAULT_ENDPOINT)); phase(record, "APPLE_LOGIN")
        phase(record, "WAITING_FOR_2FA")
        assertTrue(runCatching { record.submit(nonce(record), arrayOf("bad"), true) }.isFailure)
        reply(record, arrayOf("123456")); phase(record, "APPLE_LOGIN")
        choices(record, "WAITING_FOR_TEAM_SELECTION")
        assertTrue(runCatching { record.submit(null, arrayOf("unknown"), false) }.isFailure)
        assertTrue(runCatching { record.submit(null, arrayOf("A", "B"), false) }.isFailure)
        record.submit(null, arrayOf("B"), false); assertArrayEquals(arrayOf("B"), record.awaitReply())
        for (step in listOf("REGISTERING_DEVICE", "DOWNLOADING_SIDESTORE")) phase(record, step)
        for (progress in listOf(0.25, 1.0)) record.event(JSONObject().put("phase", "DOWNLOADING_SIDESTORE").put("progress", progress).put("password", "not-forwarded"))
        assertFalse(record.snapshot().toString().contains("not-forwarded")); phase(record, "SIGNING")
        choices(record, "WAITING_FOR_CERTIFICATE_REVOCATION")
        assertTrue(record.pending()) // No default or automatic revocation.
        assertTrue(runCatching { record.submit(null, emptyArray(), false) }.isFailure)
        assertTrue(runCatching { record.submit(null, arrayOf("A", "A"), false) }.isFailure)
        record.submit(null, arrayOf("A", "B"), false); record.awaitReply().fill("")
        for (step in listOf("SIGNING", "INSTALLING", "GENERATING_PAIRING", "PLACING_PAIRING", "COMPLETED")) phase(record, step)
        assertEquals("RUNNING", record.snapshot().getString("state")) // Reaping/cleanup precede completion.
        record.complete(); assertEquals("COMPLETED", record.snapshot().getString("state"))
        record.fail("USB_DETACHED"); assertEquals("COMPLETED", record.snapshot().getString("state"))
    }
    @Test fun cancellationAndDetachAreFinalAtEveryInteractiveAndLongStage() {
        val steps = listOf("PAIRING", "WAITING_FOR_TRUST", "READING_DEVICE_INFORMATION", "WAITING_FOR_LOGIN", "APPLE_LOGIN",
            "WAITING_FOR_2FA", "APPLE_LOGIN", "WAITING_FOR_TEAM_SELECTION", "REGISTERING_DEVICE", "DOWNLOADING_SIDESTORE",
            "SIGNING", "WAITING_FOR_CERTIFICATE_REVOCATION", "SIGNING", "INSTALLING", "GENERATING_PAIRING", "PLACING_PAIRING")
        for (index in steps.indices) for (code in listOf("CANCELED", "USB_DETACHED")) {
            val record = record()
            for ((position, step) in steps.take(index + 1).withIndex()) {
                if (step in setOf("WAITING_FOR_TEAM_SELECTION", "WAITING_FOR_CERTIFICATE_REVOCATION")) choices(record, step) else phase(record, step)
                if (record.pending() && position < index) {
                    val kind = record.snapshot().getJSONObject("prompt").getString("kind")
                    val values = when (kind) { "LOGIN" -> arrayOf("account", "password", HostToolConversation.DEFAULT_ENDPOINT); "CODE" -> arrayOf("123456"); else -> arrayOf("A") }
                    record.submit(nonce(record), values, kind in setOf("LOGIN", "CODE")); record.awaitReply().fill("")
                }
            }
            record.fail(code); assertEquals(code, record.failure())
            assertFalse(record.pending()); assertTrue(runCatching { record.awaitReply(1) }.isFailure)
            phase(record, "SIGNING"); assertEquals(code, record.failure())
        }
    }
    @Test fun pendingInputTimeoutAndCancellationWakeWaitersAndDiscardQueuedSecrets() {
        val record = record(); login(record)
        assertTrue(runCatching { record.awaitReply(1) }.isFailure); assertEquals("INPUT_TIMEOUT", record.failure())
        val queued = record(); login(queued); queued.submit(nonce(queued), arrayOf("account", "password", HostToolConversation.DEFAULT_ENDPOINT), true)
        queued.fail("CANCELED"); assertTrue(runCatching { queued.awaitReply(1) }.isFailure)
        val waiting = record(); login(waiting)
        val thread = Thread { assertTrue(runCatching { waiting.awaitReply() }.isFailure) }.apply { start() }
        waiting.fail("USB_DETACHED"); thread.join(1000); assertFalse(thread.isAlive)
    }
    @Test fun invalidStateTransitionsUntrustedMetadataAndRawErrorsAreRejectedOrRedacted() {
        val record = record(); assertTrue(runCatching { phase(record, "SIGNING") }.isFailure)
        assertTrue(runCatching { record.complete() }.isFailure)
        record.fail("password-sentinel and cookie-token"); assertEquals("WORKER_PROTOCOL", record.failure())
        assertFalse(record.snapshot().toString().contains("sentinel"))
        for ((upstream, stable) in listOf("SIDESTORE_DOWNLOAD" to "DOWNLOAD", "HOUSE_ARREST_AFC" to "AFC_PLACEMENT")) {
            val failure = record(); failure.fail(upstream); assertEquals(stable, failure.failure())
        }
    }
    @Test fun anisetteEndpointsRequireHostHttpsWithoutCredentialsOrQuery() {
        assertTrue(HostToolConversation.endpoint(HostToolConversation.DEFAULT_ENDPOINT))
        for (url in listOf("http://localhost", "https://user:password@host", "https://host/?password=secret", "https://host/#fragment", "https:///missing", "https://host:22")) {
            assertFalse(url, HostToolConversation.endpoint(url))
        }
    }
}
