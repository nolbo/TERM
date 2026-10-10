package dev.terminal.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.*
import dev.terminal.app.ui.WorkspaceEditScreen
import dev.terminal.runtime.session.SessionProfile

class WorkspaceEditActivity : WorkspaceSettingsActivity() {
    private var profiles by mutableStateOf(emptyList<SessionProfile>())

    override fun onResume() {
        super.onResume()
        profiles = SessionProfiles(this).all()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requestedId = intent.getStringExtra(EXTRA_ID)
        compose { WorkspaceEditScreen(requestedId, state, profiles, error, saving || !connected,
            onBack = { finish() },
            onSave = { workspace -> mutate({ finish() }) { it.saveWorkspace(workspace.json().toString()) } },
            onDelete = { id -> mutate({ finish() }) { it.deleteWorkspace(id) } },
            onRun = { workspace, launch ->
                runCatching {
                    val store = SessionProfiles(this)
                    val profile = store.all().first { it.id == launch.profileId }
                    val spec = launch.resolve(profile, store::resolve)
                    startActivity(Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra("workspace-launch", spec.toJson().toString()).putExtra("workspace-id", workspace.id))
                }.onFailure { error = it.message }
            }) }
    }

    companion object {
        private const val EXTRA_ID = "workspaceId"
        fun intent(context: Context, workspaceId: String? = null) = Intent(context, WorkspaceEditActivity::class.java)
            .putExtra(EXTRA_ID, workspaceId)
    }
}
