package dev.terminal.app

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.MutableState
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.app.ui.ProfileInstallationState
import dev.terminal.app.ui.ProfileInstallationStatus
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.ipc.IRuntimeStateListener
import dev.terminal.runtime.session.Environment
import dev.terminal.runtime.session.EnvironmentId
import dev.terminal.runtime.session.EnvironmentRegistry
import dev.terminal.runtime.session.SessionLaunchSpec
import dev.terminal.runtime.session.SessionProfile
import dev.terminal.runtime.session.SessionRuntime
import dev.terminal.runtime.session.SessionStorage
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class ProfileInstallationConsentTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val runtimeField = MainActivity::class.java.getDeclaredField("runtime").apply { isAccessible = true }
    private val selectProfile = MainActivity::class.java.getDeclaredMethod("selectProfile", SessionProfile::class.java)
        .apply { isAccessible = true }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { message }
            SystemClock.sleep(100)
        }
    }

    private fun runtime(scenario: ActivityScenario<MainActivity>): IRuntimeService {
        var result: IRuntimeService? = null
        await("Runtime did not connect") { scenario.onActivity { result = runtimeField.get(it) as IRuntimeService? }; result != null }
        return checkNotNull(result)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> state(activity: MainActivity, name: String) = MainActivity::class.java
        .getDeclaredField("${name}\$delegate").apply { isAccessible = true }.get(activity) as MutableState<T>

    private fun find(text: String): AccessibilityNodeInfo? {
        return findNode { it.text?.toString() == text }
    }

    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.clearCache()
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && predicate(node)) return node
            for (index in 0 until node.childCount) walk(node.getChild(index))?.let { return it }
            return null
        }
        return walk(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun click(resource: Int) {
        await("Button missing: ${context.getString(resource)}") {
            var node = find(context.getString(resource))
            while (node != null && !node.isClickable) node = node.parent
            node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
    }

    private fun fixture(runtime: SessionRuntime): Pair<Environment, SessionProfile> {
        val environment = Environment.create(runtime, "Install consent ${UUID.randomUUID()}")
        return environment to SessionProfiles(context).save(SessionProfile(UUID.randomUUID().toString(), environment.name,
            runtime, "/bin/bash"), environment)
    }

    private fun remove(environment: Environment, profile: SessionProfile) {
        SessionProfiles(context).delete(profile.id)
        EnvironmentRegistry(context.filesDir).transaction { delete(environment.id) }
    }

    private fun proxy(original: IRuntimeService, status: (String) -> String,
        requests: MutableList<Pair<String, SessionLaunchSpec>>): IRuntimeService =
        Proxy.newProxyInstance(IRuntimeService::class.java.classLoader, arrayOf(IRuntimeService::class.java)) { _, method, args ->
            when (method.name) {
                "getEnvironmentSoftwareJson" -> status(args!![0] as String)
                "createSessionFromSpec", "createSessionFromSpecWithRequestId", "createSessionFromSpecWithInstallationConsent" -> {
                    requests += method.name to SessionLaunchSpec.fromJson(args!![0] as String)
                    null
                }
                else -> method.invoke(original, *(args ?: emptyArray()))
            }
        } as IRuntimeService

    @Test fun everyMissingDistributionAndAvfRequireConsentAndRestoreTheQuestion() {
        for (kind in listOf(SessionRuntime.ROOTLESS, SessionRuntime.ROOTLESS_UBUNTU, SessionRuntime.ROOTLESS_ARCH, SessionRuntime.AVF)) {
            val (environment, profile) = fixture(kind)
            val requests = CopyOnWriteArrayList<Pair<String, SessionLaunchSpec>>()
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    var original = runtime(scenario)
                    fun mock() = scenario.onActivity { runtimeField.set(it, proxy(original,
                        { """{"installed":false,"busy":false}""" }, requests)) }
                    mock()
                    scenario.onActivity { selectProfile.invoke(it, profile) }
                    await("Missing image must ask first") { find(context.getString(R.string.profile_installation_title)) != null }
                    assertTrue(requests.isEmpty())
                    click(R.string.cancel)
                    await("Cancel must dismiss the question") { find(context.getString(R.string.profile_installation_title)) == null }
                    assertTrue(requests.isEmpty())
                    scenario.onActivity { selectProfile.invoke(it, profile) }
                    await("Question must reopen") { find(context.getString(R.string.profile_installation_title)) != null }
                    runtimeFieldReset(scenario, original)
                    scenario.recreate()
                    original = runtime(scenario)
                    mock()
                    await("Recreation must retain the question") { find(context.getString(R.string.profile_installation_title)) != null }
                    assertTrue(requests.isEmpty())
                    if (kind == SessionRuntime.ROOTLESS) scenario.onActivity { runtimeField.set(it, null) }
                    click(R.string.install)
                    if (kind == SessionRuntime.ROOTLESS) {
                        scenario.onActivity {
                            assertEquals(environment.id, state<SessionLaunchSpec?>(it, "profileInstallationToLaunch").value?.environmentId)
                            assertTrue("Consent must wait for the service", requests.isEmpty())
                        }
                        mock()
                    }
                    await("Approval must dispatch exactly one launch") { requests.size == 1 }
                    assertEquals("createSessionFromSpecWithInstallationConsent", requests.single().first)
                    assertEquals(environment.id, requests.single().second.environmentId)
                    scenario.onActivity {
                        runtimeField.set(it, original)
                        MainActivity::class.java.getDeclaredField("rootlessInstallInFlight").apply { isAccessible = true }.setBoolean(it, false)
                        state<Boolean>(it, "rootlessVisible").value = false
                    }
                }
            } finally { remove(environment, profile) }
        }
    }

    private fun runtimeFieldReset(scenario: ActivityScenario<MainActivity>, service: IRuntimeService) =
        scenario.onActivity { runtimeField.set(it, service) }

    @Test fun installedAndInstallingProfilesOpenWithoutAnotherInstallationApproval() {
        for (kind in listOf(SessionRuntime.ROOTLESS, SessionRuntime.AVF)) {
            val (environment, profile) = fixture(kind)
            val requests = CopyOnWriteArrayList<Pair<String, SessionLaunchSpec>>()
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    val original = runtime(scenario)
                    var status = """{"installed":true,"available":true,"state":"READY"}"""
                    scenario.onActivity { runtimeField.set(it, proxy(original, { status }, requests)); selectProfile.invoke(it, profile) }
                    await("Installed profile must open") { requests.size == 1 }
                    assertFalse(requests.single().first.endsWith("InstallationConsent"))
                    status = """{"installed":false,"busy":true,"state":"CHECKING"}"""
                    scenario.onActivity { selectProfile.invoke(it, profile) }
                    await("Installing profile must join existing work") { requests.size == 2 }
                    assertTrue(requests.none { it.first.endsWith("InstallationConsent") })
                    assertNull(find(context.getString(R.string.profile_installation_title)))
                    if (kind == SessionRuntime.ROOTLESS) {
                        scenario.onActivity { selectProfile.invoke(it, profile) }
                        assertEquals("Reopening progress must not create another request", 2, requests.size)
                    }
                    scenario.onActivity {
                        runtimeField.set(it, original)
                        MainActivity::class.java.getDeclaredField("rootlessInstallInFlight").apply { isAccessible = true }.setBoolean(it, false)
                        state<Boolean>(it, "rootlessVisible").value = false
                    }
                }
            } finally { remove(environment, profile) }
        }
    }

    @Test fun bothNewSessionMenusShowEnvironmentInstallationStatesAndRefreshThem() {
        val fixtures = listOf(SessionRuntime.ROOTLESS, SessionRuntime.ROOTLESS_UBUNTU, SessionRuntime.AVF).map(::fixture)
        val requests = CopyOnWriteArrayList<Pair<String, SessionLaunchSpec>>()
        val statuses = java.util.concurrent.ConcurrentHashMap(fixtures.mapIndexed { index, (environment, _) ->
            environment.id.value to when (index) {
                0 -> """{"installed":true,"available":true}"""
                1 -> """{"installed":false,"busy":false}"""
                else -> """{"installed":false,"busy":true}"""
            }
        }.toMap())
        val preferences = context.getSharedPreferences(SettingsDetailActivity.TERMINAL_PREFERENCES, android.content.Context.MODE_PRIVATE)
        val oldToolbar = preferences.getString(KEY_TOOLBAR, null)
        preferences.edit().putString(KEY_TOOLBAR, serializeToolbarConfig(ToolbarConfig(tools = listOf(SESSIONS_TOOL)))).commit()
        var nativeSessionId: String? = null
        var nativeService: IRuntimeService? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val original = runtime(scenario)
                nativeService = original
                original.startRuntime()
                await("Native runtime must be ready") { original.state == "READY" }
                fun sessionIds() = JSONArray(original.sessionsJson).let { array ->
                    (0 until array.length()).map { array.getJSONObject(it).getString("id") }.toSet()
                }
                val before = sessionIds()
                original.createSession("NATIVE", "/system/bin/sh", "", 24, 80)
                await("Create the toolbar's Native session") { nativeSessionId = (sessionIds() - before).singleOrNull(); nativeSessionId != null }
                scenario.onActivity { MainActivity::class.java.getDeclaredMethod("attach", String::class.java)
                    .apply { isAccessible = true }.invoke(it, nativeSessionId) }
                scenario.onActivity { runtimeField.set(it, proxy(original, { id -> statuses[id] ?: "{}" }, requests)) }
                for (menu in listOf("showNewSessionSheet", "showSessionProfilePicker")) {
                    scenario.onActivity {
                        MainActivity::class.java.getDeclaredMethod(menu).apply { isAccessible = true }.invoke(it)
                        state<List<SessionProfile>>(it, "profiles").value = fixtures.map { pair -> pair.second }
                    }
                    for (label in listOf(R.string.profile_installation_installed, R.string.profile_installation_missing, R.string.profile_installation_installing)) {
                        await("Menu $menu must show ${context.getString(label)}") {
                            if (find(context.getString(label)) != null) true
                            else { findNode { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); false }
                        }
                    }
                    statuses[fixtures[1].first.id.value] = """{"installed":true,"available":true}"""
                    await("Visible menu must refresh installation state") {
                        var refreshed = false
                        scenario.onActivity { refreshed = state<Map<EnvironmentId, ProfileInstallationStatus>>(it, "profileInstallationStatuses")
                            .value[fixtures[1].first.id]?.state == ProfileInstallationState.INSTALLED }
                        refreshed
                    }
                    assertTrue("Opening a menu must not install or launch", requests.isEmpty())
                    scenario.onActivity {
                        state<Boolean>(it, "newSessionSheetVisible").value = false
                        state<Boolean>(it, "sessionProfilePickerVisible").value = false
                    }
                    statuses[fixtures[1].first.id.value] = """{"installed":false,"busy":false}"""
                }
                runtimeFieldReset(scenario, original)
            }
        } finally {
            nativeSessionId?.let { nativeService?.terminateSession(it) }
            preferences.edit().apply { if (oldToolbar == null) remove(KEY_TOOLBAR) else putString(KEY_TOOLBAR, oldToolbar) }.commit()
            fixtures.forEach { (environment, profile) -> remove(environment, profile) }
        }
    }

    @Test fun ordinarySessionRequestsRejectMissingAndIncompleteRootfsWithoutInstalling() {
        for (partial in listOf(false, true)) {
            val (environment, profile) = fixture(SessionRuntime.ROOTLESS)
            val errors = CopyOnWriteArrayList<String>()
            val root = SessionStorage.root(context.filesDir, profile.runtime, profile.storageId)
            if (partial) File(root, "rootfs/partial").apply { parentFile!!.mkdirs(); writeText("preserve") }
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    val service = runtime(scenario)
                    val listener = object : IRuntimeStateListener.Stub() {
                        override fun onRuntimeStateChanged(state: String, detail: String) = Unit
                        override fun onSessionsChanged(revision: Long, json: String) = Unit
                        override fun onSessionFocusRequested(sessionId: String) = Unit
                        override fun onSessionExited(sessionId: String, code: Int) = Unit
                        override fun onSessionTerminationFailed(sessionId: String, message: String) = Unit
                        override fun onTerminalOutput(id: String, bytes: ByteArray, replay: Boolean) = Unit
                        override fun onRootlessProgress(runtimeType: String, phase: String, detail: String) {
                            if (runtimeType.endsWith(profile.storageId)) errors += "unexpected progress: $phase"
                        }
                        override fun onOperationError(operation: String, message: String) {
                            if (operation.contains(profile.storageId)) errors += message
                        }
                    }
                    service.registerListener(listener)
                    try {
                        val before = JSONArray(service.sessionsJson).length()
                        service.createSessionFromSpec(SessionProfiles(context).resolve(profile).toJson().toString(), 24, 80)
                        await("Ordinary launch must require consent") { errors.isNotEmpty() }
                        assertEquals(1, errors.size)
                        assertTrue(errors.single(), errors.single().contains("설치를 승인"))
                        assertFalse(ProfileInstallationStatus.fromJson(service.getEnvironmentSoftwareJson(environment.id.value))
                            .state == ProfileInstallationState.INSTALLED)
                        assertEquals(before, JSONArray(service.sessionsJson).length())
                        if (partial) assertEquals("preserve", File(root, "rootfs/partial").readText())
                        else assertFalse(File(root, "rootfs").exists())
                    } finally { service.unregisterListener(listener) }
                }
            } finally { remove(environment, profile) }
        }
    }
}
