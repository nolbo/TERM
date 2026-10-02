package dev.terminal.app

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import dev.terminal.app.ui.TerminalChromeColors
import org.junit.Assert.*
import org.junit.Test

class ProfilePaletteTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun inheritanceCopiesDuplicationResetAndPersistence() {
        val store = SessionProfiles(context)
        val preferences = context.getSharedPreferences("palette-test-${java.util.UUID.randomUUID()}", Context.MODE_PRIVATE)
        val inherited = store.duplicate(store.builtIns().first()).copy(name = "Palette inherited")
        val initial = TerminalAppearance.paletteColors(preferences)
        val custom = initial.copy(background = "#F0E0D0")
        val independent = store.duplicate(inherited).copy(palette = initial.toList())
        val imported = store.duplicate(independent).copy(palette = custom.toList())
        val duplicate = store.duplicate(imported)
        val fixtures = listOf(inherited, independent, imported, duplicate)
        try {
            fixtures.forEach(store::save)
            assertNull(inherited.palette)
            assertNull(store.duplicate(inherited).palette)
            assertEquals(custom.toList(), SessionProfiles(context).find(duplicate.id)!!.palette)
            assertNotEquals(imported.id, duplicate.id)
            TerminalAppearance.saveCustomPalette(preferences, custom)
            assertEquals(custom, TerminalAppearance.profilePalette(preferences, inherited))
            assertEquals(initial, TerminalAppearance.profilePalette(preferences, independent))
            // A copy from an inheriting source is still a snapshot, not another inheritance link.
            store.save(imported.copy(palette = TerminalAppearance.profilePalette(preferences, inherited).toList()))
            TerminalAppearance.saveCustomPalette(preferences, initial)
            assertEquals(custom, TerminalAppearance.profilePalette(preferences, store.find(imported.id)))
            store.save(imported.copy(palette = null))
            assertNull(SessionProfiles(context).find(imported.id)!!.palette)
            assertEquals(initial, TerminalAppearance.profilePalette(preferences, store.find(imported.id)))
            TerminalAppearance.saveCustomPalette(preferences, custom)
            assertEquals(custom, TerminalAppearance.profilePalette(preferences, store.find(imported.id)))
        } finally {
            fixtures.forEach { store.delete(it.id) }
            preferences.edit().clear().commit()
        }
    }

    @Test fun editorImportsIntoDraftSurvivesRecreationAndResetRestoresInheritance() {
        val store = SessionProfiles(context)
        val global = context.getSharedPreferences(SettingsDetailActivity.TERMINAL_PREFERENCES, Context.MODE_PRIVATE)
        val originalGlobal = TerminalAppearance.paletteColors(global)
        val profile = store.duplicate(store.builtIns().first()).copy(name = "Palette target")
        val source = store.duplicate(profile).copy(name = "Palette source",
            palette = originalGlobal.copy(background = "#F0E0D0").toList())
        try {
            store.save(profile); store.save(source)
            ActivityScenario.launch<ProfileEditActivity>(Intent(context, ProfileEditActivity::class.java)
                .putExtra("profileId", profile.id)).use { activity ->
                click(R.string.profile_palette_import)
                click(source.name)
                assertNull(store.find(profile.id)!!.palette)
                activity.recreate()
                click(R.string.profile_save)
                await { store.find(profile.id)?.palette == source.palette }
            }
            assertEquals(originalGlobal, TerminalAppearance.paletteColors(global))
            ActivityScenario.launch<ProfileEditActivity>(Intent(context, ProfileEditActivity::class.java)
                .putExtra("profileId", profile.id)).use {
                click(R.string.profile_palette_copy_global)
                click(R.string.profile_save)
                await { store.find(profile.id)?.palette == originalGlobal.toList() }
            }
            ActivityScenario.launch<ProfileEditActivity>(Intent(context, ProfileEditActivity::class.java)
                .putExtra("profileId", profile.id)).use {
                click(R.string.profile_palette_reset)
                click(R.string.profile_save)
                await { store.find(profile.id)?.palette == null }
            }
        } finally { store.delete(profile.id); store.delete(source.id) }
    }

    @Test fun colorEditorReturnsToProfileDraftWithoutChangingGlobalPalette() {
        val store = SessionProfiles(context)
        val global = context.getSharedPreferences(SettingsDetailActivity.TERMINAL_PREFERENCES, Context.MODE_PRIVATE)
        val originalGlobal = TerminalAppearance.paletteColors(global)
        val profile = store.duplicate(store.builtIns().first()).copy(name = "Palette editor test")
        try {
            store.save(profile)
            ActivityScenario.launch<ProfileEditActivity>(Intent(context, ProfileEditActivity::class.java)
                .putExtra("profileId", profile.id)).use { activity ->
                click(R.string.customize_palette)
                click(R.string.palette_background)
                await { find { it.isEditable } != null }
                val arguments = android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "#345678")
                }
                assertTrue(find { it.isEditable }!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
                click(R.string.palette_apply_color)
                click(R.string.save)
                await { find { it.text?.toString() == context.getString(R.string.profile_palette_custom) } != null }
                assertNull(store.find(profile.id)!!.palette)
                assertEquals(originalGlobal, TerminalAppearance.paletteColors(global))
                activity.recreate()
                click(R.string.profile_save)
                await { store.find(profile.id)?.palette?.get(1) == "#345678" }
            }
            assertEquals(originalGlobal, TerminalAppearance.paletteColors(global))
        } finally { store.delete(profile.id) }
    }

    @Test fun transitionRendersIntermediateColorsAndRetargetsWithoutMutatingEmulators() {
        instrumentation.runOnMainSync {
            val preferences = context.getSharedPreferences("transition-test", Context.MODE_PRIVATE)
            val backends = mutableListOf<RemoteTerminalBackend>()
            var chrome: TerminalChromeColors? = null
            val transition = TerminalPaletteTransition { chrome = it }
            val bitmap = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
            try {
                val view = TerminalView(context, null).apply {
                    setTerminalViewClient(TerminalInputCallbacks())
                    TerminalAppearance.apply(preferences, this, applyPalette = false)
                    layout(0, 0, 600, 400)
                }
                fun session() = TerminalSession("", "/", emptyArray(), emptyArray(), 1000, TerminalCallbacks {},
                    RemoteTerminalBackend(write = {}, resize = { _, _ -> }).also { backends += it })
                val dark = session()
                val light = session()
                val colors = TerminalAppearance.paletteColors(preferences)
                view.attachSession(dark)
                dark.emulator.mColors.setDefaultColors(colors.copy(background = "#102030").asProperties())
                transition.show(view, false)
                view.draw(Canvas(bitmap))
                assertEquals(0xFF102030.toInt(), bitmap.getPixel(590, 390))
                transition.capture(view)
                view.attachSession(light)
                light.emulator.mColors.setDefaultColors(colors.copy(background = "#F0E0D0").asProperties())
                transition.show(view, true)
                val animatorField = TerminalPaletteTransition::class.java.getDeclaredField("animator").apply { isAccessible = true }
                val animator = animatorField.get(transition) as? ValueAnimator
                if (ValueAnimator.areAnimatorsEnabled()) {
                    assertNotNull(animator)
                    animator!!.currentPlayTime = 120
                    view.draw(Canvas(bitmap))
                    val halfway = bitmap.getPixel(590, 390)
                    assertNotEquals(0xFF102030.toInt(), halfway)
                    assertNotEquals(0xFFF0E0D0.toInt(), halfway)
                    assertEquals(0xFFF0E0D0.toInt(), light.emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND])
                    assertEquals(0xFF102030.toInt(), dark.emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND])
                    transition.capture(view)
                    view.attachSession(dark)
                    transition.show(view, true)
                    view.draw(Canvas(bitmap))
                    assertEquals(halfway, bitmap.getPixel(590, 390))
                    (animatorField.get(transition) as ValueAnimator).end()
                } else {
                    view.attachSession(dark)
                    transition.show(view, false)
                }
                view.draw(Canvas(bitmap))
                assertEquals(0xFF102030.toInt(), bitmap.getPixel(590, 390))
                assertEquals(TerminalChromeColors(androidx.compose.ui.graphics.Color(0xFF102030)), chrome)
                light.emulator.mColors.reset(TextStyle.COLOR_INDEX_BACKGROUND)
                assertEquals(0xFFF0E0D0.toInt(), light.emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND])
            } finally {
                transition.cancel()
                bitmap.recycle()
                backends.forEach { it.close() }
                preferences.edit().clear().commit()
            }
        }
    }

    @Test fun liveSessionsUseTheirProfilePaletteAcrossSwitchResumeAndRecreation() {
        val store = SessionProfiles(context)
        val global = context.getSharedPreferences(SettingsDetailActivity.TERMINAL_PREFERENCES, Context.MODE_PRIVATE)
        val base = TerminalAppearance.paletteColors(global)
        val first = store.duplicate(store.builtIns().first()).copy(name = "Palette Night",
            palette = base.copy(background = "#102030").toList())
        val second = store.duplicate(first).copy(name = "Palette Day", palette = base.copy(background = "#F0E0D0").toList())
        val queue = java.util.concurrent.LinkedBlockingQueue<dev.terminal.runtime.ipc.IRuntimeService>()
        val connection = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName, binder: android.os.IBinder) {
                queue.offer(dev.terminal.runtime.ipc.IRuntimeService.Stub.asInterface(binder))
            }
            override fun onServiceDisconnected(name: android.content.ComponentName) = Unit
        }
        assertTrue(context.bindService(Intent(context, dev.terminal.runtime.lifecycle.VmRuntimeService::class.java),
            connection, Context.BIND_AUTO_CREATE))
        val service = requireNotNull(queue.poll(15, java.util.concurrent.TimeUnit.SECONDS))
        val owned = mutableListOf<String>()
        fun sessions(): List<org.json.JSONObject> {
            val array = org.json.JSONArray(service.sessionsJson)
            return List(array.length()) { array.getJSONObject(it) }
        }
        try {
            service.startRuntime()
            await { service.state == "READY" }
            for (profile in listOf(first, second)) {
                store.save(profile)
                service.createSessionFromSpec(profile.resolve().toJson().toString(), 24, 80)
                await { sessions().any { it.optString("profileId") == profile.id } }
                owned += sessions().first { it.optString("profileId") == profile.id }.getString("id")
            }
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                val viewField = MainActivity::class.java.getDeclaredField("terminalView").apply { isAccessible = true }
                val attach = MainActivity::class.java.getDeclaredMethod("attach", String::class.java).apply { isAccessible = true }
                fun select(id: String, background: String) {
                    await {
                        var matched = false
                        activity.onActivity {
                            attach.invoke(it, id)
                            val view = viewField.get(it) as? TerminalView
                            matched = view?.currentSession?.emulator?.mColors?.mCurrentColors
                                ?.get(TextStyle.COLOR_INDEX_BACKGROUND) == android.graphics.Color.parseColor(background)
                        }
                        matched
                    }
                }
                select(owned[0], "#102030")
                select(owned[1], "#F0E0D0")
                select(owned[0], "#102030")
                activity.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                store.save(first.copy(palette = null))
                activity.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                select(owned[0], base.background)
                select(owned[1], "#F0E0D0")
                activity.recreate()
                select(owned[1], "#F0E0D0")
                assertEquals(2, sessions().count { it.getString("id") in owned })
            }
        } finally {
            owned.forEach { service.terminateSession(it) }
            store.delete(first.id); store.delete(second.id)
            context.unbindService(connection)
        }
    }

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (predicate(node)) return node
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        instrumentation.waitForIdleSync()
        return walk(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun click(id: Int) = click(context.getString(id))
    private fun click(text: String) {
        await {
            if (find { it.hasLabel(text) && it.isVisibleToUser } != null) true else {
                find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                false
            }
        }
        var node = requireNotNull(find { it.hasLabel(text) && it.isVisibleToUser })
        while (!node.isClickable) node = requireNotNull(node.parent)
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition()) { check(SystemClock.elapsedRealtime() < deadline) { "Timed out" }; SystemClock.sleep(100) }
    }
}
