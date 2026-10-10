package dev.terminal.app

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
            choose(R.string.profile_storage, context.getString(R.string.profile_storage_existing))
            assertEveryEnvironmentListed(store)
            click(current.name)
            assertSelected(current)
            click(target.name)
            assertSingleEnvironmentRow(target)
            activity.recreate()
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
        click(R.string.save)
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
