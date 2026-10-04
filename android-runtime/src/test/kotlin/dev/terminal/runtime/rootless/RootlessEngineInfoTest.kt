package dev.terminal.runtime.rootless

import dev.terminal.runtime.native.NativeExecutableLoader
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootlessEngineInfoTest {
    @get:Rule val temporary = TemporaryFolder()
    private val loader = object : NativeExecutableLoader {
        override fun resolvePackagedExecutable(name: String) = Result.success(File("/apk/lib$name.so"))
    }

    @Test fun unsupportedAbiDoesNotClaimRuntimeSupportOrProbeExecutables() {
        val info = RootlessEngineInfo.inspect(listOf("x86_64"), loader, temporary.newFile(), true) {
            fail("Unsupported executable must not run"); null
        }
        assertFalse(info.arm64)
        assertFalse(info.prootAvailable)
        assertFalse(info.loaderAvailable)
        assertFalse(info.acceleration)
        assertNull(info.prootVersion)
    }

    @Test fun accelerationRequiresTheApkProfileAndItsReadableLibrary() {
        val library = temporary.newFile()
        val active = RootlessEngineInfo.inspect(listOf("arm64-v8a"), loader, library, true) { "5.1.107" }
        assertTrue(active.prootAvailable)
        assertTrue(active.acceleration)
        assertTrue(active.faststat)
        assertEquals("5.1.107", active.prootVersion)
        assertFalse(RootlessEngineInfo.inspect(listOf("arm64-v8a"), loader, library, false) { null }.acceleration)
        assertFalse(RootlessEngineInfo.inspect(listOf("arm64-v8a"), loader, File("/missing"), true) { null }.faststat)
    }

    @Test fun missingLoaderAndUnknownVersionAreReportedHonestly() {
        val missingLoader = object : NativeExecutableLoader {
            override fun resolvePackagedExecutable(name: String) = if (name == "proot-loader")
                Result.failure<File>(IllegalStateException("Missing loader")) else loader.resolvePackagedExecutable(name)
        }
        val info = RootlessEngineInfo.inspect(listOf("arm64-v8a"), missingLoader, temporary.newFile(), true) { null }
        assertFalse(info.prootAvailable)
        assertFalse(info.loaderAvailable)
        assertFalse(info.acceleration)
        assertNull(info.prootVersion)
    }
}
