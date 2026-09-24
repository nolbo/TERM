package dev.terminal.app

import android.graphics.Bitmap
import android.graphics.Color
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.runtime.session.SessionLaunchSpec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class ProfileIconsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun rendererAdaptsMaskTintButPreservesOriginalColors() {
        val store = ProfileIconStore(context)
        fun imageId(mask: Boolean): String {
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            if (mask) for (y in 4..11) for (x in 4..11) bitmap.setPixel(x, y, Color.BLACK)
            else bitmap.eraseColor(Color.RED)
            val data = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
            bitmap.recycle()
            return store.import(ByteArrayInputStream(data))
        }
        val mask = imageId(true); val original = imageId(false)
        assertTrue(mask.endsWith(":mask")); assertTrue(original.endsWith(":color"))
        val tint = mutableStateOf(androidx.compose.ui.graphics.Color.Green)
        val centers = java.util.concurrent.ConcurrentHashMap<Int, Offset>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(ProfilesActivity::class.java).use { activity ->
            activity.onActivity { screen ->
                screen.setContent {
                    Row(Modifier.padding(top = 100.dp)) {
                        listOf(mask, original).forEachIndexed { index, icon ->
                            dev.terminal.app.ui.ProfileIcon(icon, "NATIVE", size = 64.dp, tint = tint.value,
                                modifier = Modifier.onGloballyPositioned { centers[index] = it.boundsInWindow().center })
                        }
                    }
                }
            }
            fun assertColors(expectedTint: Int) {
                val end = SystemClock.elapsedRealtime() + 20_000
                while (true) {
                    instrumentation.waitForIdleSync()
                    val screenshot = instrumentation.uiAutomation.takeScreenshot()
                    val matches = try {
                        centers.size == 2 && screenshot != null &&
                            screenshot.getPixel(centers.getValue(0).x.toInt(), centers.getValue(0).y.toInt()) == expectedTint &&
                            screenshot.getPixel(centers.getValue(1).x.toInt(), centers.getValue(1).y.toInt()) == Color.RED
                    } finally { screenshot?.recycle() }
                    if (matches) return
                    check(SystemClock.elapsedRealtime() < end) { "Rendered icon tint did not match" }
                    SystemClock.sleep(100)
                }
            }
            assertColors(Color.GREEN)
            activity.onActivity { tint.value = androidx.compose.ui.graphics.Color.Blue }
            assertColors(Color.BLUE)
        }
    }

    @Test fun editorIconSelectionCanBeCancelledAndSurvivesRecreationAndSave() {
        val store = SessionProfiles(context)
        val profile = store.duplicate(store.builtIns().first()).copy(name = "Icon editor fixture")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (predicate(node)) return node
                for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
                return null
            }
            instrumentation.waitForIdleSync()
            return walk(instrumentation.uiAutomation.rootInActiveWindow)
        }
        fun await(check: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 20_000
            while (!check()) { check(SystemClock.elapsedRealtime() < end) { "Icon UI timeout" }; SystemClock.sleep(100) }
        }
        fun click(label: Int) {
            await {
                var node = find { it.text?.toString() == context.getString(label) }
                while (node != null && !node.isClickable) node = node.parent
                node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
        }
        fun chooseKeyboard() {
            await {
                if (find { it.text?.toString() == context.getString(R.string.profile_icon_keyboard) } != null) true
                else { find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); false }
            }
            click(R.string.profile_icon_keyboard)
        }
        try {
            store.save(profile)
            ActivityScenario.launch<ProfileEditActivity>(Intent(context, ProfileEditActivity::class.java)
                .putExtra("profileId", profile.id)).use { activity ->
                click(R.string.profile_icon); chooseKeyboard(); click(R.string.cancel)
                await { find { it.text?.toString() == context.getString(R.string.cancel) } == null }
                assertEquals("auto", store.find(profile.id)?.icon)
                click(R.string.profile_icon); chooseKeyboard()
                activity.recreate()
                click(R.string.save)
                await { find { it.text?.toString() == context.getString(R.string.cancel) } == null }
                await {
                    if (find { it.text?.toString() == context.getString(R.string.profile_save) } != null) true
                    else { find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); false }
                }
                click(R.string.profile_save)
                await { store.find(profile.id)?.icon == "keyboard" }
            }
        } finally { store.delete(profile.id) }
    }

    @Test fun transparentMonochromeUsesTintWhileColorsAndOpaqueBackgroundsArePreserved() {
        val mono = Bitmap.createBitmap(12, 10, Bitmap.Config.ARGB_8888)
        mono.setPixel(5, 5, Color.WHITE)
        mono.setPixel(6, 5, Color.argb(128, 255, 255, 255))
        val mask = ProfileIconStore.normalize(mono)
        assertTrue(mask.tintable)
        assertEquals(128, mask.bitmap.width)
        mask.bitmap.recycle()
        mono.setPixel(6, 5, Color.RED)
        val color = ProfileIconStore.normalize(mono)
        assertFalse(color.tintable)
        val center = color.bitmap.getPixel(100, 64)
        assertTrue(Color.red(center) > 200 && Color.green(center) < 20)
        color.bitmap.recycle(); mono.recycle()
        val opaque = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val original = ProfileIconStore.normalize(opaque)
        assertFalse(original.tintable)
        original.bitmap.recycle(); opaque.recycle()
    }

    @Test fun paddingIsRemovedAndAspectRatioIsPreservedForVeryWideImages() {
        val source = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        for (x in 100 until 300) for (y in 90 until 110) source.setPixel(x, y, Color.BLACK)
        val result = ProfileIconStore.normalize(source)
        val points = buildList {
            for (y in 0 until 128) for (x in 0 until 128) if (Color.alpha(result.bitmap.getPixel(x, y)) > 0) add(x to y)
        }
        assertEquals(8, points.minOf { it.first })
        assertEquals(119, points.maxOf { it.first })
        assertTrue(points.maxOf { it.second } - points.minOf { it.second } in 10..12)
        assertTrue(points.minOf { it.second } > 50)
        result.bitmap.recycle(); source.recycle()
    }

    @Test fun importedIconSurvivesDuplicationProfileDeletionAndLaunchSnapshot() {
        val bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { setPixel(10, 10, Color.BLUE) }
        val bytes = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        bitmap.recycle()
        val icons = ProfileIconStore(context)
        val source = java.io.File(context.cacheDir, "app-updates/profile-icon-test-${java.util.UUID.randomUUID()}.png")
        source.parentFile!!.mkdirs(); source.writeBytes(bytes)
        val icon = try {
            icons.import(FileProvider.getUriForFile(context, context.packageName + ".app-updates", source))
        } finally { source.delete() }
        assertTrue(icon.endsWith(":mask"))
        assertEquals(icon, icons.import(ByteArrayInputStream(bytes)))
        assertTrue(icons.all().contains(icon))
        assertNull(icons.file("image:../../secret:mask"))
        val profiles = SessionProfiles(context)
        val first = profiles.duplicate(profiles.builtIns().first()).copy(icon = icon)
        val copy = profiles.duplicate(first)
        try {
            profiles.save(first); profiles.save(copy)
            val launch = SessionLaunchSpec.fromJson(first.resolve().toJson().toString())
            profiles.save(first.copy(icon = "terminal"))
            profiles.delete(first.id)
            assertEquals(icon, launch.icon)
            assertEquals(icon, SessionProfiles(context).find(copy.id)?.icon)
            assertTrue(requireNotNull(icons.file(icon)).isFile)
        } finally { profiles.delete(first.id); profiles.delete(copy.id) }
    }

    @Test fun malformedOversizedAndEmptyImagesAreRejected() {
        val store = ProfileIconStore(context)
        assertTrue(runCatching { store.import(ByteArrayInputStream("not an image".toByteArray())) }.isFailure)
        assertTrue(runCatching { store.import(ByteArrayInputStream(ByteArray(8 * 1024 * 1024 + 1))) }.isFailure)
        val transparent = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        assertTrue(runCatching { ProfileIconStore.normalize(transparent) }.isFailure)
        transparent.recycle()
    }
}
