package dev.terminal.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.lifecycle.VmRuntimeService
import dev.terminal.runtime.notifications.SessionNotificationSettings
import dev.terminal.runtime.session.SessionRuntime
import dev.terminal.runtime.workspace.Workspace
import dev.terminal.runtime.workspace.WorkspaceRegistry
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceResumeDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val store get() = (context.applicationContext as TerminalApplication).terminalSessions
    private val activeSession = MainActivity::class.java.getDeclaredMethod("getActiveSessionId").apply { isAccessible = true }
    private val runtimeField = MainActivity::class.java.getDeclaredField("runtime").apply { isAccessible = true }
    private val attach = MainActivity::class.java.getDeclaredMethod("attach", String::class.java).apply { isAccessible = true }
    private val selectWorkspace = MainActivity::class.java.getDeclaredMethod("selectWorkspaceFilter", String::class.java).apply { isAccessible = true }

    private data class Fixture(
        val runtime: IRuntimeService,
        val workspace: Workspace,
        val sessionId: String,
        val otherWorkspace: Workspace,
        val otherSessionId: String,
        val sessionIds: Set<String>,
        val memberships: Map<String, String>,
    )

    @Test fun longBackgroundResumePreservesWorkspaceAndActiveSession() = withFixture { fixture ->
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            selectFixture(scenario, fixture)
            backgroundUntilDetached(scenario, fixture.sessionId)
            scenario.moveToState(Lifecycle.State.RESUMED)
            awaitSelection(scenario, fixture.runtime, fixture.workspace.id, fixture.sessionId)
            assertSessionsUnchanged(fixture)
        }
    }

    @Test fun launcherReturnAfterBackgroundGracePreservesWorkspaceAndActiveSession() = withFixture { fixture ->
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            selectFixture(scenario, fixture)
            val marker = UUID.randomUUID().toString()
            fixture.runtime.writeSessionInput(fixture.sessionId, "TERM_WORKSPACE_RESUME='$marker'\n".toByteArray())
            await("The shell accepted the marker") {
                var accepted = false
                instrumentation.runOnMainSync {
                    accepted = store.sessions[fixture.sessionId]?.emulator?.screen?.transcriptText
                        ?.filterNot(Char::isWhitespace)?.contains(marker) == true
                }
                accepted
            }
            backgroundUntilDetached(scenario, fixture.sessionId)
            scenario.moveToState(Lifecycle.State.RESUMED)
            // Match SetupActivity's forwarded console Intent while retaining Scenario tracking.
            scenario.onActivity { activity ->
                activity.startActivity(Intent(activity.intent)
                    .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            instrumentation.waitForIdleSync()
            awaitSelection(scenario, fixture.runtime, fixture.workspace.id, fixture.sessionId)
            fixture.runtime.writeSessionInput(fixture.sessionId,
                "printf 'resume-proof:%s\\n' \"\$TERM_WORKSPACE_RESUME\"\n".toByteArray())
            await("The original shell variable survives launcher return") {
                var survived = false
                instrumentation.runOnMainSync {
                    survived = store.sessions[fixture.sessionId]?.emulator?.screen?.transcriptText
                        ?.filterNot(Char::isWhitespace)?.contains("resume-proof:$marker") == true
                }
                survived
            }
            assertSessionsUnchanged(fixture)
        }
    }

    @Test fun newConsoleAndRecreationRestoreSavedWorkspaceAndSession() = withFixture { fixture ->
        context.getSharedPreferences("terminal-ui-state", Context.MODE_PRIVATE).edit()
            .putString("active-session", fixture.sessionId).commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitSelection(scenario, fixture.runtime, fixture.workspace.id, fixture.sessionId)
            scenario.recreate()
            awaitSelection(scenario, fixture.runtime, fixture.workspace.id, fixture.sessionId)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitSelection(scenario, fixture.runtime, fixture.workspace.id, fixture.sessionId)
            assertSessionsUnchanged(fixture)
        }
    }

    @Test fun explicitSessionNotificationStillSelectsItsOwnWorkspace() = withFixture { fixture ->
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            selectFixture(scenario, fixture)
            scenario.onActivity { activity ->
                activity.startActivity(Intent(activity.intent)
                    .putExtra(SessionNotificationSettings.EXTRA_SESSION_ID, fixture.otherSessionId)
                    .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            awaitSelection(scenario, fixture.runtime, fixture.otherWorkspace.id, fixture.otherSessionId)
            assertSessionsUnchanged(fixture)
        }
    }

    private fun selectFixture(scenario: ActivityScenario<MainActivity>, fixture: Fixture) {
        await("The console connects") {
            var connected = false
            scenario.onActivity { connected = runtimeField.get(it) != null }
            connected
        }
        scenario.onActivity { selectWorkspace.invoke(it, fixture.workspace.id) }
        await("The Workspace session projection is available") {
            var selected = false
            scenario.onActivity {
                attach.invoke(it, fixture.sessionId)
                selected = activeSession.invoke(it) == fixture.sessionId
            }
            selected
        }
        awaitSelection(scenario, fixture.runtime, fixture.workspace.id, fixture.sessionId)
    }

    private fun backgroundUntilDetached(scenario: ActivityScenario<MainActivity>, sessionId: String) {
        scenario.moveToState(Lifecycle.State.CREATED)
        await("The background grace period releases the UI mirror") {
            var detached = false
            instrumentation.runOnMainSync { detached = !store.hasMirror(sessionId) }
            detached
        }
    }

    private fun awaitSelection(scenario: ActivityScenario<MainActivity>, runtime: IRuntimeService, workspaceId: String, sessionId: String) {
        await("The previous Workspace and active tab are restored") {
            var restored = false
            scenario.onActivity {
                restored = activeSession.invoke(it) == sessionId && store.hasMirror(sessionId) &&
                    store.sessions[sessionId]?.emulator != null
            }
            restored && state(runtime).selected == workspaceId
        }
    }

    private fun assertSessionsUnchanged(fixture: Fixture) {
        assertEquals(fixture.sessionIds, ids(fixture.runtime))
        assertEquals(fixture.memberships, state(fixture.runtime).memberships)
    }

    private fun withFixture(work: (Fixture) -> Unit) {
        val queue = LinkedBlockingQueue<IRuntimeService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { queue.offer(IRuntimeService.Stub.asInterface(binder)) }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
        val runtime = checkNotNull(queue.poll(10, TimeUnit.SECONDS))
        val before = state(runtime)
        val preferences = context.getSharedPreferences("terminal-ui-state", Context.MODE_PRIVATE)
        val previousSession = preferences.getString("active-session", null)
        val workspace = Workspace(UUID.randomUUID().toString(), "Resume workspace", false)
        val other = Workspace(UUID.randomUUID().toString(), "Other resume workspace", false)
        val owned = mutableSetOf<String>()
        try {
            assertEquals("", runtime.saveWorkspace(workspace.json().toString()))
            assertEquals("", runtime.saveWorkspace(other.json().toString()))
            val profiles = SessionProfiles(context)
            val profile = profiles.all().first { it.runtime == SessionRuntime.NATIVE }
            val spec = profiles.resolve(profile.copy(shell = "/system/bin/sh", shellArguments = emptyList(),
                workingDirectory = context.filesDir.path, startupCommand = ""))
            fun create(workspaceId: String?): String {
                val existing = ids(runtime)
                runtime.createSessionInWorkspace(spec.toJson().toString(), UUID.randomUUID().toString(), workspaceId, false, 24, 80)
                var created: String? = null
                await("Fixture Native session is running") {
                    created = (ids(runtime) - existing).singleOrNull()
                    created != null
                }
                return checkNotNull(created).also { owned += it }
            }
            create(workspace.id)
            val selected = create(workspace.id)
            val otherSession = create(other.id)
            create(null)
            assertEquals("", runtime.selectWorkspace(workspace.id))
            work(Fixture(runtime, workspace, selected, other, otherSession, ids(runtime), state(runtime).memberships))
        } finally {
            owned.forEach(runtime::terminateSession)
            await("Fixture sessions exit") { ids(runtime).none { it in owned } }
            runtime.deleteWorkspace(workspace.id)
            runtime.deleteWorkspace(other.id)
            runtime.selectWorkspace(before.selected)
            preferences.edit().putString("active-session", previousSession).commit()
            context.unbindService(connection)
        }
    }

    private fun state(runtime: IRuntimeService) = WorkspaceRegistry.State.parse(runtime.workspacesJson)
    private fun ids(runtime: IRuntimeService): Set<String> = JSONArray(runtime.sessionsJson).let { rows ->
        (0 until rows.length()).mapTo(mutableSetOf()) { rows.getJSONObject(it).getString("id") }
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        throw AssertionError(message)
    }
}
