package dev.terminal.app.update

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class AppUpdateManifestTest {
    private val commit = "1".repeat(40)
    private val payload = "signed APK test payload".toByteArray()
    private fun json(channel: UpdateChannel = UpdateChannel.MAIN) = JSONObject().put("schemaVersion", 1)
        .put("applicationId", channel.applicationId)
        .put("commit", commit).put("versionCode", 32).put("versionName", "0.1.0+${channel.wireName}.32.${commit.take(12)}")
        .put("size", payload.size).put("sha256", payload.sha256Hex()).put("minSdk", 31)
        .apply { if (channel != UpdateChannel.MAIN) put("channel", channel.wireName) }
    private fun manifest(channel: UpdateChannel = UpdateChannel.MAIN) = AppUpdateManifest.parse(json(channel).toString(), channel)

    @Test fun installedProductionPackagesHaveExactlyOneChannelAndDebugHasNone() {
        assertEquals(UpdateChannel.MAIN, UpdateChannel.fromApplicationId("dev.terminal.app"))
        assertEquals(UpdateChannel.DEV, UpdateChannel.fromApplicationId("dev.terminal.app.prerelease"))
        assertNull(UpdateChannel.fromApplicationId("dev.terminal.app.debug"))
        assertNull(UpdateChannel.fromApplicationId("other.app"))
    }

    @Test fun releaseNotesArePinnedToTheSameChannelAndCommitAsTheApk() {
        for (channel in UpdateChannel.entries) {
            val update = manifest().copy(channel = channel)
            assertEquals("https://github.com/nolbo/TERM/releases/download/${channel.wireName}-$commit/RELEASE_NOTES.ko.md", update.releaseNotesUrl)
        }
        assertNull(manifest().releaseNotes)
    }

    @Test fun remoteTextPreservesKoreanAndLineBreaksAndRejectsOversizedResponses() {
        val notes = "# 새 버전\n\n- 업데이트 안내\n".toByteArray(Charsets.UTF_8)
        assertEquals(String(notes, Charsets.UTF_8), readUpdateText(ByteArrayInputStream(notes), notes.size))
        assertEquals("", readUpdateText(ByteArrayInputStream(byteArrayOf()), MAX_RELEASE_NOTES_BYTES))
        assertThrows(IOException::class.java) {
            readUpdateText(ByteArrayInputStream(notes), notes.size - 1)
        }
        assertThrows(IOException::class.java) {
            readUpdateText(ByteArrayInputStream(ByteArray(MAX_RELEASE_NOTES_BYTES + 1)), MAX_RELEASE_NOTES_BYTES)
        }
    }

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
        for (channel in UpdateChannel.entries) {
            val update = manifest(channel)
            validateUpdateIdentity(update, channel.applicationId, 32, update.versionName, setOf("trusted"), setOf("trusted"))
            for (signers in listOf(emptySet(), setOf("other"), setOf("trusted", "other"))) {
                assertEquals(UpdateRejection.SIGNATURE, assertThrows(UpdateRejected::class.java) {
                    validateUpdateIdentity(update, channel.applicationId, 32, update.versionName, setOf("trusted"), signers)
                }.reason)
            }
            assertThrows(UpdateRejected::class.java) { validateUpdateIdentity(update, channel.applicationId, 32, update.versionName, emptySet(), setOf("trusted")) }
            assertThrows(UpdateRejected::class.java) { validateUpdateIdentity(update, "other.app", 32, update.versionName, setOf("trusted"), setOf("trusted")) }
            assertThrows(UpdateRejected::class.java) { validateUpdateIdentity(update, channel.applicationId, 31, update.versionName, setOf("trusted"), setOf("trusted")) }
            assertThrows(UpdateRejected::class.java) { validateUpdateIdentity(update, channel.applicationId, 32, "spoofed", setOf("trusted"), setOf("trusted")) }
            val otherPackage = UpdateChannel.entries.single { it != channel }.applicationId
            assertEquals(UpdateRejection.IDENTITY, assertThrows(UpdateRejected::class.java) {
                validateUpdateIdentity(update, otherPackage, 32, update.versionName, setOf("trusted"), setOf("trusted"))
            }.reason)
        }
    }

    @Test fun developmentManifestCannotLeakIntoTheStableChannelOrUseAnotherEndpoint() {
        val dev = json(UpdateChannel.DEV).toString()
        val update = AppUpdateManifest.parse(dev, UpdateChannel.DEV)
        assertEquals("https://github.com/nolbo/TERM/releases/download/dev-$commit/TERM.apk", update.apkUrl)
        assertEquals("https://github.com/nolbo/TERM/releases/download/dev-latest/update.json", update.channel.manifestUrl)
        assertThrows(IllegalArgumentException::class.java) { AppUpdateManifest.parse(dev) }
        assertThrows(IllegalArgumentException::class.java) { AppUpdateManifest.parse(json().toString(), UpdateChannel.DEV) }
        assertThrows(IllegalArgumentException::class.java) { AppUpdateManifest.parse(json().put("channel", "../latest").toString()) }
    }

    @Test fun channelAndPackageMustMatchIncludingHistoricalDevelopmentManifests() {
        for (channel in UpdateChannel.entries) {
            val otherPackage = UpdateChannel.entries.single { it != channel }.applicationId
            assertThrows(IllegalArgumentException::class.java) {
                AppUpdateManifest.parse(json(channel).put("applicationId", otherPackage).toString(), channel)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppUpdateManifest.parse(json(UpdateChannel.DEV).put("applicationId", "dev.terminal.app.debug").toString(), UpdateChannel.DEV)
        }
    }

    @Test fun legacyStableManifestsRemainReadableAndVersionOrderingStaysForwardOnly() {
        assertEquals(UpdateChannel.MAIN, manifest().channel)
        assertEquals(UpdateChannel.MAIN, AppUpdateManifest.parse(json().put("channel", "main").toString()).channel)
        val main = manifest().copy(versionCode = 1000005)
        assertEquals(UpdateAvailability.AVAILABLE, updateAvailability(36, commit, 35, main))
        assertEquals(UpdateAvailability.AVAILABLE, updateAvailability(1000004, commit, 35, main))
        assertEquals(UpdateAvailability.OLDER, updateAvailability(1000004, commit, 35, manifest()))
    }
}
