package dev.terminal.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.app.ui.runtimeTitleResource
import dev.terminal.runtime.session.Environment
import dev.terminal.runtime.session.EnvironmentRegistry
import dev.terminal.runtime.session.SessionProfile
import dev.terminal.runtime.session.SessionRuntime
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProfileEnvironmentSelectionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun newProfileDefaultNameIsVisibleRestoredAndSavedWithoutManualInput() {
        val store = SessionProfiles(context)
        val originalProfiles = store.all().map { it.id }.toSet()
        val originalEnvironments = store.environments().map { it.id }.toSet()
        val expected = EnvironmentRegistry.defaultName("Native", store.all().map { it.name })
        val expectedEnvironment = EnvironmentRegistry.defaultName("env-$expected", store.environments().map { it.name })
        var saved: SessionProfile? = null
        try {
            ActivityScenario.launch(ProfileEditActivity::class.java).use { activity ->
                assertChoice(R.string.profile_storage_name, expectedEnvironment)
                click(R.string.profile_name)
                await { nodes().any { it.isEditable } }
                assertEquals("The creation screen decides the default name", expected,
                    nodes().first { it.isEditable }.text?.toString())
                activity.recreate()
                await { nodes().any { it.isEditable && it.text?.toString() == expected } }
                click(R.string.cancel)
                assertChoice(R.string.profile_storage_name, expectedEnvironment)
                assertEquals(originalProfiles, store.all().map { it.id }.toSet())
                assertEquals(originalEnvironments, store.environments().map { it.id }.toSet())
                click(R.string.profile_save)
                await { store.all().any { it.id !in originalProfiles && it.name == expected } }
                val profile = store.all().single { it.id !in originalProfiles && it.name == expected }
                saved = profile
                assertEquals(expected, profile.name)
                assertEquals(expectedEnvironment, store.environments().single { it.id == profile.environmentId }.name)
                assertEquals(originalEnvironments + requireNotNull(profile.environmentId),
                    store.environments().map { it.id }.toSet())
            }
        } finally {
            saved?.let { profile ->
                store.delete(profile.id)
                EnvironmentRegistry(context.filesDir).transaction(store.builtIns()) {
                    delete(requireNotNull(profile.environmentId))
                }
            }
        }
    }

    @Test fun environmentDefaultNameTracksTheProfileUntilAnEnvironmentNameIsConfirmed() {
        val store = SessionProfiles(context)
        val name = "Environment default ${UUID.randomUUID()}"
        val customEnvironmentName = "My environment ${UUID.randomUUID()}"
        var saved: SessionProfile? = null
        try {
            ActivityScenario.launch(ProfileEditActivity::class.java).use { activity ->
                setField(R.string.profile_name, name)
                assertChoice(R.string.profile_storage_name, "env-$name")
                setField(R.string.profile_name, "$name renamed")
                activity.recreate()
                assertChoice(R.string.profile_storage_name, "env-$name renamed")
                setField(R.string.profile_storage_name, customEnvironmentName)
                setField(R.string.profile_name, name)
                activity.recreate()
                assertChoice(R.string.profile_storage_name, customEnvironmentName)
                assertTrue(store.environments().none { it.name == customEnvironmentName })
                click(R.string.profile_save)
                await { store.all().any { it.name == name } }
                val profile = store.all().single { it.name == name }
                saved = profile
                assertEquals(customEnvironmentName, store.environments().single { it.id == profile.environmentId }.name)
            }
        } finally {
            saved?.let { profile ->
                store.delete(profile.id)
                EnvironmentRegistry(context.filesDir).transaction(store.builtIns()) {
                    delete(requireNotNull(profile.environmentId))
                }
            }
        }
    }

    @Test fun defaultNameFollowsSelectionsUntilAManualNameIsConfirmed() = withEnvironments { store, fixtures ->
        val (targetProfile, target) = fixtures.last()
        store.save(targetProfile.copy(name = target.name))
        val existingNames = store.all().map { it.name }
        val manualName = "My profile ${UUID.randomUUID()}"
        var saved: SessionProfile? = null
        try {
            ActivityScenario.launch(ProfileEditActivity::class.java).use { activity ->
                choose(R.string.profile_runtime, context.getString(R.string.runtime_rootless_title))
                assertChoice(R.string.profile_name, EnvironmentRegistry.defaultName("Debian", existingNames))
                activity.recreate()
                choose(R.string.profile_distribution, context.getString(R.string.runtime_ubuntu_title))
                assertChoice(R.string.profile_name, EnvironmentRegistry.defaultName("Ubuntu 24.04 LTS", existingNames))
                // Cancelling a name edit must leave automatic naming enabled.
                click(R.string.profile_name)
                click(R.string.cancel)
                choose(R.string.profile_storage, context.getString(R.string.profile_storage_existing))
                click(target.name)
                assertChoice(R.string.profile_name, "${target.name} (2)")
                activity.recreate()
                assertChoice(R.string.profile_name, "${target.name} (2)")
                choose(R.string.profile_storage, context.getString(R.string.profile_storage_new))
                assertChoice(R.string.profile_name, EnvironmentRegistry.defaultName("Arch Linux ARM", existingNames))
                setField(R.string.profile_name, manualName)
                activity.recreate()
                choose(R.string.profile_runtime, context.getString(R.string.runtime_native_title))
                assertChoice(R.string.profile_name, manualName)
                assertTrue("Names remain draft-only until saving", store.all().none { it.name == manualName })
                click(R.string.profile_save)
                await { store.all().any { it.name == manualName } }
                saved = store.all().single { it.name == manualName }
                assertEquals(SessionRuntime.NATIVE, saved?.runtime)
            }
        } finally {
            saved?.let { profile ->
                store.delete(profile.id)
                EnvironmentRegistry(context.filesDir).transaction(store.builtIns()) {
                    delete(requireNotNull(profile.environmentId))
                }
            }
        }
    }

    @Test fun duplicateKeepsItsNameWhenTheRuntimeChangesAndTheScreenIsRecreated() = withEnvironments { store, fixtures ->
        val (profile, environment) = fixtures.first()
        val originalProfiles = store.all().map { it.id }.toSet()
        ActivityScenario.launch<ProfileEditActivity>(Intent(context, ProfileEditActivity::class.java)
            .putExtra("profileId", profile.id).putExtra("duplicate", true)).use { activity ->
            assertChoice(R.string.profile_name, "${profile.name} copy")
            assertChoice(R.string.profile_storage, context.getString(R.string.profile_storage_existing))
            assertSelected(environment)
            activity.recreate()
            assertChoice(R.string.profile_storage, context.getString(R.string.profile_storage_existing))
            assertSelected(environment)
            choose(R.string.profile_storage, context.getString(R.string.profile_storage_new))
            choose(R.string.profile_runtime, context.getString(R.string.runtime_rootless_title))
            activity.recreate()
            assertChoice(R.string.profile_name, "${profile.name} copy")
        }
        assertEquals(originalProfiles, store.all().map { it.id }.toSet())
    }

    @Test fun creationShowsNativeStorageAccessAndOpensAndroidPermissionSettings() {
        val store = SessionProfiles(context)
        val original = store.environments().map { it.id }.toSet()
        val osGrant = android.os.Environment.isExternalStorageManager()
        ActivityScenario.launch(ProfileEditActivity::class.java).use { activity ->
            fun storageRow() = scrollTo(context.getString(R.string.environment_storage_access), clickable = true)
            assertTrue(storageRow().isCheckable)
            assertEquals(osGrant, storageRow().isChecked)
            val permissionRow = scrollTo(context.getString(R.string.environment_storage_android_permission), clickable = true)
            assertFalse(permissionRow.isCheckable)
            assertTrue(storageRow().performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == "com.android.settings" }
            assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            await { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == context.packageName }
            assertEquals(osGrant, storageRow().isChecked)
            activity.recreate()
            assertTrue(storageRow().isCheckable)
            assertEquals(osGrant, storageRow().isChecked)
        }
        assertEquals(osGrant, android.os.Environment.isExternalStorageManager())
        assertEquals(original, store.environments().map { it.id }.toSet())
    }

    @Test fun storageAccessCreationDraftSurvivesRecreationAndCommitsOnlyOnSave() = withEnvironments { store, _ ->
        val name = "Storage choice ${UUID.randomUUID()}"
        val original = store.environments().map { it.id }.toSet()
        try {
            ActivityScenario.launch(ProfileEditActivity::class.java).use { activity ->
                setField(R.string.profile_name, name)
                choose(R.string.profile_runtime, context.getString(R.string.runtime_rootless_title))
                fun storageRow() = scrollTo(context.getString(R.string.environment_storage_access), clickable = true)
                assertTrue(storageRow().isCheckable)
                assertFalse(storageRow().isChecked)
                assertTrue(storageRow().performAction(AccessibilityNodeInfo.ACTION_CLICK))
                settle()
                assertTrue(storageRow().isChecked)
                assertEquals(original, store.environments().map { it.id }.toSet())
                activity.recreate()
                assertTrue(storageRow().isChecked)
                choose(R.string.profile_storage, context.getString(R.string.profile_storage_existing))
                choose(R.string.profile_storage, context.getString(R.string.profile_storage_new))
                assertTrue(storageRow().isChecked)
                click(R.string.profile_save)
                await { store.all().any { it.name == name } }
            }
            val saved = store.all().single { it.name == name }
            val environment = store.environments().single { it.id == saved.environmentId }
            assertTrue(environment.internalStorageAccess)
            assertEquals(original + environment.id, store.environments().map { it.id }.toSet())
        } finally {
            val owned = store.all().filter { it.name == name }
            owned.forEach { store.delete(it.id) }
            EnvironmentRegistry(context.filesDir).transaction(store.builtIns()) {
                owned.mapNotNull { it.environmentId }.forEach { delete(it) }
            }
        }
    }

    @Test fun environmentPermissionOptionsPersistIndependentlyAndNativeShowsTheActualCommonGrant() = withEnvironments { store, fixtures ->
        val osGrant = android.os.Environment.isExternalStorageManager()
        for (environment in fixtures.map { it.second }.filter { it.sessionRuntime in setOf(SessionRuntime.ROOTLESS, SessionRuntime.AVF) }) {
            ActivityScenario.launch<EnvironmentDetailActivity>(EnvironmentDetailActivity.intent(context, environment.id.value)).use { activity ->
                fun storageRow() = scrollTo(context.getString(R.string.environment_storage_access), clickable = true)
                assertFalse(storageRow().isChecked)
                assertTrue(storageRow().performAction(AccessibilityNodeInfo.ACTION_CLICK))
                await { store.environments().single { it.id == environment.id }.internalStorageAccess }
                activity.recreate()
                assertTrue(storageRow().isChecked)
                assertTrue(storageRow().performAction(AccessibilityNodeInfo.ACTION_CLICK))
                await { !store.environments().single { it.id == environment.id }.internalStorageAccess }
            }
        }
        val native = fixtures.single { it.second.sessionRuntime == SessionRuntime.NATIVE }.second
        ActivityScenario.launch<EnvironmentDetailActivity>(EnvironmentDetailActivity.intent(context, native.id.value)).use {
            val row = scrollTo(context.getString(R.string.environment_storage_access), clickable = true)
            assertTrue(row.isCheckable)
            assertEquals(osGrant, row.isChecked)
            scrollTo(context.getString(if (osGrant) R.string.environment_storage_native_enabled else R.string.environment_storage_native_disabled))
            assertFalse(scrollTo(context.getString(R.string.environment_storage_android_permission), clickable = true).isCheckable)
        }
        assertEquals(osGrant, android.os.Environment.isExternalStorageManager())
        assertTrue(fixtures.all { !store.environments().single { row -> row.id == it.second.id }.internalStorageAccess })
    }

    @Test fun creationListsAllEnvironmentsAndAdoptsTheSelectedRuntimeAndDistribution() = withEnvironments { store, fixtures ->
        val originalEnvironments = store.environments().map { it.id }.toSet()
        val name = "Existing environment profile ${UUID.randomUUID()}"
        val target = fixtures.last().second
        try {
            ActivityScenario.launch(ProfileEditActivity::class.java).use { activity ->
                setField(R.string.profile_name, name)
                choose(R.string.profile_runtime, context.getString(R.string.runtime_rootless_title))
                choose(R.string.profile_distribution, context.getString(runtimeTitleResource(SessionRuntime.ROOTLESS_UBUNTU.wireName)))
                choose(R.string.profile_storage, context.getString(R.string.profile_storage_existing))
                assertEveryEnvironmentListed(store)
                click(target.name)
                assertSingleEnvironmentRow(target)
                activity.recreate()
                assertSelected(target)
                assertSingleEnvironmentRow(target)
                choose(R.string.profile_storage, context.getString(R.string.profile_storage_new))
                assertChoice(R.string.profile_runtime, context.getString(R.string.runtime_rootless_title))
                assertChoice(R.string.profile_distribution, context.getString(runtimeTitleResource(target.sessionRuntime.wireName)))
                choose(R.string.profile_storage, context.getString(R.string.profile_storage_existing))
                click(target.name)
                assertTrue("Selection only changes the draft", store.all().none { it.name == name })
                click(R.string.profile_save)
                await { store.all().any { it.name == name } }
            }
            val saved = store.all().single { it.name == name }
            assertEnvironment(saved, target)
            val defaults = store.builtIns().first { it.runtime == target.sessionRuntime }
            assertEquals(defaults.shell, saved.shell)
            assertEquals(defaults.shellArguments, saved.shellArguments)
            assertEquals(defaults.workingDirectory, saved.workingDirectory)
            assertEquals("Reusing an environment must not create another one", originalEnvironments, store.environments().map { it.id }.toSet())
        } finally { store.all().filter { it.name == name }.forEach { store.delete(it.id) } }
    }

    @Test fun editingIncludesTheCurrentEnvironmentAndKeepRestoresItsRuntime() = withEnvironments { store, fixtures ->
        val (profile, current) = fixtures.first()
        val target = fixtures.last().second
        ActivityScenario.launch<ProfileEditActivity>(Intent(context, ProfileEditActivity::class.java)
            .putExtra("profileId", profile.id)).use { activity ->
            choose(R.string.profile_storage, context.getString(R.string.profile_storage_new))
            choose(R.string.profile_runtime, context.getString(R.string.runtime_rootless_title))
            choose(R.string.profile_distribution, context.getString(runtimeTitleResource(SessionRuntime.ROOTLESS_UBUNTU.wireName)))
            assertChoice(R.string.profile_name, profile.name)
            choose(R.string.profile_storage, context.getString(R.string.profile_storage_existing))
            assertEveryEnvironmentListed(store)
            click(current.name)
            assertSelected(current)
            click(target.name)
            assertSingleEnvironmentRow(target)
            activity.recreate()
            assertChoice(R.string.profile_name, profile.name)
            assertSelected(target)
            assertEquals("Selection must not save the profile", current.id, store.find(profile.id)?.environmentId)
            choose(R.string.profile_storage, context.getString(R.string.profile_storage_current))
            choose(R.string.profile_storage, context.getString(R.string.profile_storage_new))
            assertChoice(R.string.profile_runtime, context.getString(runtimeTitleResource(current.sessionRuntime.wireName)))
            choose(R.string.profile_storage, context.getString(R.string.profile_storage_current))
            click(R.string.profile_save)
            await { activity.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
        }
        assertEquals(profile, store.find(profile.id))
    }

    @Test fun editingAdoptsAnotherRuntimeWhilePreservingCustomLaunchSettings() = withEnvironments { store, fixtures ->
        val profile = fixtures.first().first.copy(shell = "/custom/shell", shellArguments = listOf("-x", "a b"), workingDirectory = "/custom/home")
        val target = fixtures.single { it.second.sessionRuntime == SessionRuntime.AVF }.second
        store.save(profile)
        ActivityScenario.launch<ProfileEditActivity>(Intent(context, ProfileEditActivity::class.java)
            .putExtra("profileId", profile.id)).use { activity ->
            choose(R.string.profile_storage, context.getString(R.string.profile_storage_existing))
            click(target.name)
            activity.recreate()
            assertSelected(target)
            choose(R.string.profile_storage, context.getString(R.string.profile_storage_new))
            assertChoice(R.string.profile_runtime, context.getString(runtimeTitleResource(target.sessionRuntime.wireName)))
            choose(R.string.profile_storage, context.getString(R.string.profile_storage_existing))
            click(target.name)
            click(R.string.profile_save)
            await { store.find(profile.id)?.environmentId == target.id }
        }
        val saved = requireNotNull(store.find(profile.id))
        assertEnvironment(saved, target)
        assertEquals(profile.shell, saved.shell)
        assertEquals(profile.shellArguments, saved.shellArguments)
        assertEquals(profile.workingDirectory, saved.workingDirectory)
    }

    private fun withEnvironments(test: (SessionProfiles, List<Pair<SessionProfile, Environment>>) -> Unit) {
        val store = SessionProfiles(context)
        val prefix = "zzzz Profile environment ${UUID.randomUUID()}"
        val fixtures = listOf(SessionRuntime.NATIVE, SessionRuntime.ROOTLESS, SessionRuntime.ROOTLESS_UBUNTU,
            SessionRuntime.AVF, SessionRuntime.ROOTLESS_ARCH).mapIndexed { index, runtime ->
            val environment = Environment.create(runtime, "$prefix $index")
            val profile = store.save(store.duplicate(store.builtIns().first { it.runtime == runtime }), environment)
            profile to environment
        }
        try { test(store, fixtures) } finally {
            fixtures.forEach { store.delete(it.first.id) }
            EnvironmentRegistry(context.filesDir).transaction(store.builtIns()) {
                fixtures.forEach { delete(it.second.id) }
            }
        }
    }

    private fun assertEnvironment(profile: SessionProfile, environment: Environment) {
        assertEquals(environment.id, profile.environmentId)
        assertEquals(environment.sessionRuntime, profile.runtime)
        assertEquals(environment.storage.id, profile.storageId)
    }

    private fun assertEveryEnvironmentListed(store: SessionProfiles) {
        store.environments().sortedBy { it.name }.forEachIndexed { index, environment ->
            assertNotNull("Missing ${environment.sessionRuntime} environment", scrollTo(environment.name, clickable = true, fromStart = index == 0))
        }
    }

    private fun assertSelected(environment: Environment) {
        val row = scrollTo(environment.name, clickable = true)
        fun checked(node: AccessibilityNodeInfo): Boolean = node.isChecked || node.isSelected ||
            (0 until node.childCount).any { node.getChild(it)?.let(::checked) == true }
        assertTrue("The selected environment must survive recreation", checked(row))
    }

    private fun assertSingleEnvironmentRow(environment: Environment) {
        val original = scrollTo(environment.name, clickable = true)
        // An appended detail row would appear next to the storage hint, even in a long list.
        scrollTo(context.getString(R.string.profile_storage_hint))
        assertEquals("Selection must not append a duplicate detail row", 1,
            (nodes().filter { it.isVisibleToUser && it.hasLabel(environment.name) }.mapNotNull(::interactiveAncestor) + original).toSet().size)
    }

    private fun assertChoice(title: Int, value: String) {
        fun containsLabel(node: AccessibilityNodeInfo): Boolean = node.hasLabel(value) ||
            (0 until node.childCount).any { node.getChild(it)?.let(::containsLabel) == true }
        assertTrue("The execution environment determines ${context.getString(title)}",
            containsLabel(scrollTo(context.getString(title), clickable = true)))
    }

    private fun choose(title: Int, option: String) {
        click(title)
        click(option)
        click(if (title == R.string.profile_storage) R.string.confirm else R.string.save)
    }

    private fun setField(title: Int, value: String) {
        click(title)
        await { nodes().any { it.isEditable } }
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        assertTrue(nodes().first { it.isEditable }.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
        val confirm = context.getString(R.string.confirm)
        click(if (nodes().any { it.isVisibleToUser && it.hasLabel(confirm) }) R.string.confirm else R.string.save)
    }

    private fun click(id: Int) = click(context.getString(id))
    private fun click(label: String) {
        val row = scrollTo(label, clickable = true)
        // Android exposes an already-selected radio row as checkable with clicking disabled.
        if (!row.isCheckable || !row.isChecked) assertTrue(row.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        settle()
    }

    private fun scrollTo(label: String, clickable: Boolean = false, fromStart: Boolean = true): AccessibilityNodeInfo {
        fun matching(): AccessibilityNodeInfo? {
            val node = nodes().firstOrNull { it.isVisibleToUser && it.hasLabel(label) && (!clickable || interactiveAncestor(it) != null) }
                ?: return null
            return if (clickable) interactiveAncestor(node) else node
        }
        matching()?.let { return it }
        var attempts = 0
        while (fromStart && attempts++ < 25 && nodes().firstOrNull { it.isScrollable }
                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true) {
            settle()
        }
        repeat(30) {
            matching()?.let { return it }
            nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            settle()
        }
        error("Profile editor did not show $label")
    }

    private fun interactiveAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null && !current.isClickable && !current.isCheckable) current = current.parent
        return current
    }

    private fun AccessibilityNodeInfo.hasLabel(label: String) =
        text?.toString()?.lineSequence()?.any { it == label } == true || contentDescription?.toString() == label

    private fun nodes(): List<AccessibilityNodeInfo> {
        instrumentation.uiAutomation.clearCache()
        return buildList {
            fun visit(node: AccessibilityNodeInfo?) {
                if (node == null) return
                add(node)
                for (index in 0 until node.childCount) visit(node.getChild(index))
            }
            visit(instrumentation.uiAutomation.rootInActiveWindow)
        }
    }

    private fun settle() { SystemClock.sleep(300); instrumentation.waitForIdleSync() }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Profile editor did not reach the expected state" }
            settle()
        }
    }
}
