package dev.terminal.runtime.session

import org.junit.Assert.*
import org.junit.Test

class SessionProfileTest {
    @Test fun iconDefaultsSnapshotsAndCopiesRemainIndependent() {
        val original = profile().copy(icon = "keyboard")
        val snapshot = original.resolve()
        assertEquals("keyboard", SessionLaunchSpec.fromJson(snapshot.toJson().toString()).icon)
        assertEquals("keyboard", original.copy(id = "duplicate").icon)
        assertEquals("keyboard", snapshot.icon)
        assertEquals("terminal", original.copy(icon = "terminal").resolve().icon)
        assertEquals("auto", SessionLaunchSpec.fromJson(snapshot.toJson().apply { remove("icon") }.toString()).icon)
        val expected = listOf("native", "debian", "ubuntu", "arch", "avf")
        assertEquals(expected, SessionRuntime.entries.map { SessionProfileIcon.resolve("auto", it) })
        assertEquals("keyboard", SessionProfileIcon.resolve("keyboard", SessionRuntime.AVF))
        for (bad in listOf("../icon.png", "image:../file:mask", "file:///secret", "https://example.com/icon", "unknown")) {
            assertTrue(runCatching { profile().copy(icon = bad).resolve() }.isFailure)
        }
        for (mode in listOf("mask", "color")) {
            val id = "image:" + "a".repeat(64) + ":$mode"
            assertEquals(id, SessionLaunchSpec.fromJson(profile().copy(icon = id).resolve().toJson().toString()).icon)
        }
    }
    @Test fun storageSnapshotSurvivesRoundTripAndLegacyMigration() {
        val id = java.util.UUID.randomUUID().toString()
        val spec = profile().copy(storageId = id).resolve()
        assertEquals(id, SessionLaunchSpec.fromJson(spec.toJson().toString()).storageId)
        val old = spec.toJson().apply { remove("storageId") }
        assertEquals("legacy", SessionLaunchSpec.fromJson(old.toString()).storageId)
        for (bad in listOf("../native", "", "/tmp", "default/path")) {
            assertTrue(runCatching { profile().copy(storageId = bad).resolve() }.isFailure)
        }
        val base = java.io.File("/app/files")
        assertEquals(java.io.File(base, "native"), SessionStorage.root(base, SessionRuntime.NATIVE, "legacy"))
        val first = SessionStorage.root(base, SessionRuntime.ROOTLESS, id)
        val second = SessionStorage.root(base, SessionRuntime.ROOTLESS, java.util.UUID.randomUUID().toString())
        assertNotEquals(first, second)
        val layout = dev.terminal.runtime.rootless.RootlessDebianLayout(first, workspaceRoot = java.io.File(first, "workspace"))
        assertTrue(listOf(layout.rootfs, layout.home, layout.tmp, layout.workspace).all { it.parentFile == first })
        assertNotEquals(SessionStorage.root(base, SessionRuntime.AVF, id), SessionStorage.root(base, SessionRuntime.AVF, "legacy"))
    }
    @Test fun paletteRoundTripIsOptionalValidatedAndDefensivelyCopied() {
        val original = profile()
        assertNull(original.palette)
        assertNull(SessionLaunchSpec.fromJson(original.resolve().toJson().toString()).palette)
        val colors = MutableList(19) { "#123456" }
        val snapshot = original.copy(palette = colors).resolve()
        colors[0] = "#FFFFFF"
        assertEquals("#123456", snapshot.palette!![0])
        assertEquals(snapshot, SessionLaunchSpec.fromJson(snapshot.toJson().toString()))
        assertTrue(runCatching { (snapshot.palette as MutableList)[0] = "#000000" }.isFailure)
        for (bad in listOf(emptyList(), List(18) { "#123456" }, List(20) { "#123456" },
            List(19) { "#12345" }, List(19) { "red" })) {
            assertTrue(runCatching { original.copy(palette = bad).resolve() }.isFailure)
            assertTrue(runCatching {
                SessionLaunchSpec.fromJson(original.resolve().toJson().put("palette", org.json.JSONArray(bad)).toString())
            }.isFailure)
        }
        assertTrue(runCatching {
            SessionLaunchSpec.fromJson(original.resolve().toJson().put("palette", "invalid").toString())
        }.isFailure)
        assertNull(SessionLaunchSpec.fromJson(snapshot.toJson().apply { remove("palette") }.toString()).palette)
    }

    private fun profile() = SessionProfile("user-id", "Work", SessionRuntime.NATIVE, "/system/bin/sh")

    @Test fun resolveDefensivelyCopiesCollectionsAndSurvivesProfileEdits() {
        val args = mutableListOf("-l")
        val env = mutableMapOf("PROJECT" to "original")
        val profile = profile().copy(shellArguments = args, environment = env)
        val spec = profile.resolve()
        args.add("-x"); env["PROJECT"] = "changed"
        assertEquals(listOf("-l"), spec.shellArguments)
        assertEquals("original", spec.environment["PROJECT"])
        assertTrue(runCatching { (spec.environment as MutableMap)["PROJECT"] = "mutated" }.isFailure)
        assertEquals(listOf("-l", "-x"), profile.resolve().shellArguments)
    }

    @Test fun roundTripKeepsAllCreationFieldsAndNoRuntimeState() {
        val spec = profile().copy(shellArguments = listOf("-l", "space quote' 한글"),
            workingDirectory = "/workspace/a b", environment = mapOf("X" to "a=b\nline"), startupCommand = "printf hello").resolve()
        val restored = SessionLaunchSpec.fromJson(spec.toJson().toString())
        assertEquals(spec.toProfile(), restored.toProfile())
        assertFalse(spec.toJson().has("rows"))
        assertFalse(spec.toJson().has("pid"))
        assertEquals(listOf("/system/bin/sh", "-c", "printf hello\nexec \"\$@\"", "/system/bin/sh", "/system/bin/sh", "-l", "space quote' 한글"), spec.arguments())
    }

    @Test fun rejectsInvalidAndReservedValuesBeforeLaunch() {
        for (env in listOf(mapOf("A=B" to "bad"), mapOf("DROID_SESSION_ARGV" to "bad"),
            mapOf("TERMINAL_USB_TOKEN" to "bad"), mapOf("KITTY_WINDOW_ID" to "1"), mapOf("X" to "\u0000"))) {
            assertTrue(runCatching { profile().copy(environment = env).resolve() }.isFailure)
        }
        assertTrue(runCatching { profile().copy(shell = "relative").resolve() }.isFailure)
        assertTrue(runCatching { profile().copy(workingDirectory = "relative").resolve() }.isFailure)
        assertTrue(runCatching { profile().copy(shellArguments = List(65) { "a" }).resolve() }.isFailure)
    }
}
