package dev.terminal.app

import android.content.Context
import android.content.pm.PackageManager
import dev.terminal.app.update.updateStagingFile
import dev.terminal.app.update.AppUpdateController
import dev.terminal.app.update.UpdateChannel
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AppUpdateDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Suppress("DEPRECATION")
    @Test fun androidArchiveParserReturnsTheInstalledApksActualSigningCertificates() {
        val pm = context.packageManager
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val archive = pm.getPackageArchiveInfo(context.applicationInfo.sourceDir, PackageManager.GET_SIGNING_CERTIFICATES)!!
        assertTrue(installed.signingInfo!!.apkContentsSigners.isNotEmpty())
        assertEquals(installed.signingInfo!!.apkContentsSigners.toSet(), archive.signingInfo!!.apkContentsSigners.toSet())
        assertEquals(installed.longVersionCode, archive.longVersionCode)
    }

    @Test fun updateFileProviderCannotExposeOtherAppPrivateDirectories() {
        val directory = File(context.cacheDir, "app-updates").apply { mkdirs() }
        val apk = File(directory, "provider-test.apk").apply { writeBytes(byteArrayOf(1)) }
        val outside = File(context.cacheDir, "not-an-update.txt").apply { writeText("test") }
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.app-updates", apk)
            assertEquals("content", uri.scheme)
            assertThrows(IllegalArgumentException::class.java) { FileProvider.getUriForFile(context, "${context.packageName}.app-updates", outside) }
        } finally { apk.delete(); outside.delete() }
    }

    @Test fun activityRecreationKeepsTheApplicationOwnedUpdateController() {
        val app = context.applicationContext as TerminalApplication
        val controller = app.appUpdates
        ActivityScenario.launch(AppUpdatesActivity::class.java).use { scenario ->
            scenario.recreate()
            scenario.onActivity { assertSame(controller, (it.application as TerminalApplication).appUpdates) }
        }
    }

    @Suppress("DEPRECATION")
    @Test fun protectedDownloadStagingIsParseableButNotExposedByTheInstallerProvider() {
        val staging = updateStagingFile(context)
        assertTrue(staging.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
        assertFalse(staging.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
        val fixture = File(staging.parentFile!!, "parser-test.pending.apk")
        fixture.parentFile!!.mkdirs()
        try {
            File(context.applicationInfo.sourceDir).copyTo(fixture, overwrite = true)
            val archive = context.packageManager.getPackageArchiveInfo(fixture.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)!!
            assertEquals(context.packageName, archive.packageName)
            assertTrue(archive.signingInfo!!.apkContentsSigners.isNotEmpty())
            assertThrows(IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(context, "${context.packageName}.app-updates", fixture)
            }
        } finally { fixture.delete() }
    }

    @Test fun installedPackagePinsTheUpdateChannelDespiteLegacyPreferencesAndRecreation() {
        val controller = (context.applicationContext as TerminalApplication).appUpdates
        val installedChannel = UpdateChannel.fromApplicationId(context.packageName)
        val expectedChannel = installedChannel ?: UpdateChannel.fromWire(BuildConfig.UPDATE_CHANNEL)
        val preferences = context.getSharedPreferences("app-updates", Context.MODE_PRIVATE)
        val original = preferences.getString("channel", null)
        val staleChannel = if (expectedChannel == UpdateChannel.MAIN) UpdateChannel.DEV else UpdateChannel.MAIN
        try {
            assertTrue(preferences.edit().putString("channel", staleChannel.wireName).commit())
            ActivityScenario.launch(AppUpdatesActivity::class.java).use { scenario ->
                scenario.recreate()
                scenario.onActivity {
                    assertEquals(expectedChannel, controller.channel)
                    assertEquals(installedChannel != null, controller.releaseUpdatesSupported)
                    val rebuilt = AppUpdateController(context)
                    assertEquals(expectedChannel, rebuilt.channel)
                    assertEquals(installedChannel != null, rebuilt.releaseUpdatesSupported)
                }
            }
        } finally {
            assertTrue(preferences.edit().apply {
                if (original == null) remove("channel") else putString("channel", original)
            }.commit())
        }
    }
}
