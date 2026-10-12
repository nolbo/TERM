package dev.terminal.app.update

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class AppUpdateManifestTest {
    private val commit = "1".repeat(40)
    private val payload = "signed APK test payload".toByteArray()
    private fun json() = JSONObject().put("schemaVersion", 1).put("applicationId", "dev.terminal.app")
        .put("commit", commit).put("versionCode", 32).put("versionName", "0.1.0+main.32.${commit.take(12)}")
        .put("size", payload.size).put("sha256", payload.sha256Hex()).put("minSdk", 31)
    private fun manifest() = AppUpdateManifest.parse(json().toString())

    @Test fun updateComesOnlyFromTheNamedPublicRepositoryAndImmutableMainCommit() {
        val update = AppUpdateManifest.parse(json().put("apkUrl", "http://attacker.invalid/app.apk").toString())
        assertEquals("https://github.com/nolbo/TERM/releases/download/main-$commit/TERM.apk", update.apkUrl)
        assertEquals(payload.size.toLong(), update.size)
    }

    @Test fun rejectsCoercedFractionalOverflowAndInvalidSchemaVersions() {
        for (value in listOf<Any>("32", 32.5, 0, -1, 2147483648L, true)) {
            assertThrows(IllegalArgumentException::class.java) { AppUpdateManifest.parse(json().put("versionCode", value).toString()) }
        }
        assertThrows(IllegalArgumentException::class.java) { AppUpdateManifest.parse(json().put("schemaVersion", 2).toString()) }
    }

    @Test fun rejectsOtherAppsUnsafeCommitsAndUnboundedDownloads() {
        for ((key, value) in listOf("applicationId" to "another.app", "commit" to "../latest", "sha256" to "bad",
            "size" to 0, "size" to MAX_UPDATE_APK_BYTES + 1, "minSdk" to 0, "versionName" to "0.1.0+main.31.${commit.take(12)}")) {
            assertThrows(IllegalArgumentException::class.java) { AppUpdateManifest.parse(json().put(key, value).toString()) }
        }
    }

    @Test fun newerMainIsAvailableWithoutOfferingOlderOrDivergentVersions() {
        assertEquals(UpdateAvailability.AVAILABLE, updateAvailability(31, "2".repeat(40), 35, manifest()))
        assertEquals(UpdateAvailability.CURRENT, updateAvailability(32, commit, 35, manifest()))
        assertEquals(UpdateAvailability.OLDER, updateAvailability(33, "2".repeat(40), 35, manifest()))
        assertEquals(UpdateAvailability.NON_FORWARD, updateAvailability(32, "2".repeat(40), 35, manifest()))
        assertEquals(UpdateAvailability.INCOMPATIBLE, updateAvailability(31, "2".repeat(40), 30, manifest()))
    }

    @Test fun streamingVerificationAcceptsOnlyTheExactClaimedPayload() {
        val output = ByteArrayOutputStream()
        var reported = 0L
        copyVerifiedUpdate(ByteArrayInputStream(payload), output, manifest()) { reported = it }
        assertArrayEquals(payload, output.toByteArray())
        assertEquals(payload.size.toLong(), reported)
        for (bytes in listOf(payload + byteArrayOf(0), payload.dropLast(1).toByteArray(), payload.copyOf().also { it[0] = 0 })) {
            val rejected = assertThrows(UpdateRejected::class.java) { copyVerifiedUpdate(ByteArrayInputStream(bytes), ByteArrayOutputStream(), manifest()) }
            assertEquals(UpdateRejection.INTEGRITY, rejected.reason)
        }
    }

    @Test fun releaseMustMatchTheAppVersionAndEveryCurrentSigningCertificate() {
        val update = manifest()
        validateUpdateIdentity(update, "dev.terminal.app", 32, update.versionName, setOf("trusted"), setOf("trusted"))
        for (signers in listOf(emptySet(), setOf("other"), setOf("trusted", "other"))) {
            assertEquals(UpdateRejection.SIGNATURE, assertThrows(UpdateRejected::class.java) {
                validateUpdateIdentity(update, "dev.terminal.app", 32, update.versionName, setOf("trusted"), signers)
            }.reason)
        }
        assertThrows(UpdateRejected::class.java) { validateUpdateIdentity(update, "dev.terminal.app", 32, update.versionName, emptySet(), setOf("trusted")) }
        assertThrows(UpdateRejected::class.java) { validateUpdateIdentity(update, "other.app", 32, update.versionName, setOf("trusted"), setOf("trusted")) }
        assertThrows(UpdateRejected::class.java) { validateUpdateIdentity(update, "dev.terminal.app", 31, update.versionName, setOf("trusted"), setOf("trusted")) }
        assertThrows(UpdateRejected::class.java) { validateUpdateIdentity(update, "dev.terminal.app", 32, "spoofed", setOf("trusted"), setOf("trusted")) }
    }

    @Test fun developmentManifestCannotLeakIntoTheStableChannelOrUseAnotherEndpoint() {
        val dev = json().put("channel", "dev").put("versionName", "0.1.0+dev.32.${commit.take(12)}").toString()
        val update = AppUpdateManifest.parse(dev, UpdateChannel.DEV)
        assertEquals("https://github.com/nolbo/TERM/releases/download/dev-$commit/TERM.apk", update.apkUrl)
        assertEquals("https://github.com/nolbo/TERM/releases/download/dev-latest/update.json", update.channel.manifestUrl)
        assertThrows(IllegalArgumentException::class.java) { AppUpdateManifest.parse(dev) }
        assertThrows(IllegalArgumentException::class.java) { AppUpdateManifest.parse(json().toString(), UpdateChannel.DEV) }
        assertThrows(IllegalArgumentException::class.java) { AppUpdateManifest.parse(json().put("channel", "../latest").toString()) }
    }

    @Test fun legacyStableManifestsRemainReadableAndSharedCiCodesAllowChannelTransitions() {
        assertEquals(UpdateChannel.MAIN, manifest().channel)
        val dev = manifest().copy(channel = UpdateChannel.DEV, versionCode = 1000004)
        val main = manifest().copy(versionCode = 1000005)
        assertEquals(UpdateAvailability.AVAILABLE, updateAvailability(36, commit, 35, dev))
        assertEquals(UpdateAvailability.AVAILABLE, updateAvailability(dev.versionCode, commit, 35, main))
        assertEquals(UpdateAvailability.OLDER, updateAvailability(dev.versionCode, commit, 35, manifest()))
    }
}
