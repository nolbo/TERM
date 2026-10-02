package dev.terminal.app

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.app.ui.RootlessSettingsState
import dev.terminal.app.ui.RootlessSettingsStatus
import dev.terminal.app.ui.runtimeTitleResource
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.ipc.IRuntimeStateListener
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the settings actions without downloading or deleting any real distribution. */
@RunWith(AndroidJUnit4::class)
class RootlessSettingsUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun nodes(predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        instrumentation.uiAutomation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.isVisibleToUser && predicate(node)) result += node
            for (index in 0 until node.childCount) visit(node.getChild(index))
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
        return result
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { message }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(100)
        }
    }

    private fun text(id: Int) = nodes { it.text?.toString() == context.getString(id) }
    private fun icons(id: Int) = nodes { it.contentDescription?.toString() == context.getString(id) }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun clickTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current: AccessibilityNodeInfo? = node
        while (current != null && !current.isClickable) current = current.parent
        return requireNotNull(current)
    }

    private fun click(node: AccessibilityNodeInfo) {
        assertTrue(clickTarget(node).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    @Test fun imagePresenceGroupsRowsAndIconActionsKeepConfirmationAndBusyStates() {
        val intent = Intent(context, SettingsDetailActivity::class.java)
            .putExtra(SettingsDetailActivity.EXTRA_CATEGORY, SettingsDetailActivity.ROOTLESS)
        ActivityScenario.launch<SettingsDetailActivity>(intent).use { scenario ->
            val runtimeField = SettingsDetailActivity::class.java.getDeclaredField("runtime").apply { isAccessible = true }
            val statesField = SettingsDetailActivity::class.java.getDeclaredField("rootlessStates").apply { isAccessible = true }
            lateinit var states: SnapshotStateMap<String, RootlessSettingsState>
            lateinit var originalRuntime: IRuntimeService
            var connected = false
            await("Settings service did not connect") {
                scenario.onActivity { connected = runtimeField.get(it) != null }
                connected
            }
            val actions = mutableListOf<String>()
            scenario.onActivity { activity ->
                originalRuntime = runtimeField.get(activity) as IRuntimeService
                val listener = SettingsDetailActivity::class.java.getDeclaredField("listener")
                    .apply { isAccessible = true }.get(activity) as IRuntimeStateListener
                originalRuntime.unregisterListener(listener)
                @Suppress("UNCHECKED_CAST")
                val map = statesField.get(activity) as SnapshotStateMap<String, RootlessSettingsState>
                states = map
                runtimeField.set(activity, Proxy.newProxyInstance(IRuntimeService::class.java.classLoader,
                    arrayOf(IRuntimeService::class.java)) { _, method, args ->
                    if (method.name == "prepareRootless" || method.name == "removeRootless") {
                        actions += "${method.name}:${args!!.joinToString()}"
                    }
                    null
                })
                states["ROOTLESS"] = RootlessSettingsState(RootlessSettingsStatus.ERROR, "Repair failed", installed = true)
                states["ROOTLESS_UBUNTU"] = RootlessSettingsState(RootlessSettingsStatus.CHECKING, "Checking installed image", installed = true)
                states["ROOTLESS_ARCH"] = RootlessSettingsState(RootlessSettingsStatus.SETUP_REQUIRED, "Not installed", installed = false)
            }
            try {
                await("Both distribution groups and icon actions must be visible") {
                    text(R.string.rootless_installed_distributions).isNotEmpty() &&
                        text(R.string.rootless_uninstalled_distributions).isNotEmpty() &&
                        icons(R.string.install).size == 1 && icons(R.string.rootless_remove).size == 1
                }
                val installedTop = bounds(text(R.string.rootless_installed_distributions).single()).top
                val uninstalledTop = bounds(text(R.string.rootless_uninstalled_distributions).single()).top
                for (runtime in listOf("ROOTLESS", "ROOTLESS_UBUNTU")) {
                    val top = bounds(text(runtimeTitleResource(runtime)).single()).top
                    assertTrue("Installed images stay together even while checking or in error", top in installedTop until uninstalledTop)
                }
                assertTrue(bounds(text(runtimeTitleResource("ROOTLESS_ARCH")).single()).top > uninstalledTop)
                assertTrue("Install is an icon, not a text button", text(R.string.install).isEmpty())
                assertTrue("Remove is an icon, not a text button", text(R.string.rootless_remove).isEmpty())
                for (icon in icons(R.string.install) + icons(R.string.rootless_remove)) {
                    val rect = bounds(clickTarget(icon))
                    assertEquals("Icon actions remain square", rect.width(), rect.height())
                    assertTrue("Icon button must provide a 48dp touch target: $rect",
                        rect.width() >= (48 * context.resources.displayMetrics.density).toInt() - 1)
                }

                click(icons(R.string.rootless_remove).single())
                val removalTitle = context.getString(R.string.rootless_remove_title, context.getString(runtimeTitleResource("ROOTLESS")))
                await("Remove must open the distribution-specific confirmation") { nodes { it.text?.toString() == removalTitle }.isNotEmpty() }
                assertTrue("Opening confirmation must not remove an image", actions.isEmpty())
                click(text(R.string.cancel).single())
                await("Cancelling removal must return to the distribution list") { icons(R.string.install).size == 1 }

                click(icons(R.string.install).single())
                await("Install icon must invoke the selected distribution") { actions == listOf("prepareRootless:ROOTLESS_ARCH") }
                await("Busy rows must hide their action icons") { icons(R.string.install).isEmpty() }
                scenario.onActivity { assertFalse(states.getValue("ROOTLESS_ARCH").installed) }

                scenario.onActivity {
                    states["ROOTLESS_ARCH"] = RootlessSettingsState(RootlessSettingsStatus.READY, "Ready", installed = true)
                    states["ROOTLESS"] = states.getValue("ROOTLESS").copy(status = RootlessSettingsStatus.CHECKING)
                }
                await("Completed installation must move to the installed group and remove the empty group") {
                    text(R.string.rootless_uninstalled_distributions).isEmpty() && icons(R.string.rootless_remove).size == 1
                }
                click(icons(R.string.rootless_remove).single())
                await("Removal confirmation must be visible") { text(R.string.rootless_remove).isNotEmpty() }
                click(text(R.string.rootless_remove).single())
                await("Removal must keep home data by default") { actions.lastOrNull() == "removeRootless:ROOTLESS_ARCH, false" }
                scenario.onActivity {
                    assertTrue("Removing keeps the installed group until the service reports image absence", states.getValue("ROOTLESS_ARCH").installed)
                    assertEquals(RootlessSettingsStatus.CHECKING, states.getValue("ROOTLESS_ARCH").status)
                    states.toMap().forEach { (runtime, state) -> states[runtime] = state.copy(installed = false) }
                }
                await("Empty installed group must disappear") {
                    text(R.string.rootless_installed_distributions).isEmpty() && text(R.string.rootless_uninstalled_distributions).isNotEmpty()
                }
            } finally {
                scenario.onActivity { runtimeField.set(it, originalRuntime) }
            }
        }
    }
}
