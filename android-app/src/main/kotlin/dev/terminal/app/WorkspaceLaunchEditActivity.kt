package dev.terminal.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import dev.terminal.app.ui.TerminalActivity
import dev.terminal.app.ui.WorkspaceLaunchEditor

/** Returns an edit to its parent draft. It never writes Workspace or Profile metadata. */
class WorkspaceLaunchEditActivity : TerminalActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val draft = intent.getStringExtra(EXTRA_DRAFT) ?: run { finish(); return }
        val profiles = SessionProfiles(this).all()
        compose { WorkspaceLaunchEditor(draft, profiles,
            onSave = { launch ->
                setResult(RESULT_OK, Intent().putExtra(EXTRA_DRAFT, launch.json().toString()))
                finish()
            },
            onDelete = { id ->
                setResult(RESULT_OK, Intent().putExtra(EXTRA_DELETED_ID, id))
                finish()
            }, onBack = { finish() }) }
    }

    companion object {
        const val EXTRA_DRAFT = "workspaceLaunchDraft"
        const val EXTRA_DELETED_ID = "workspaceLaunchDeletedId"
        fun intent(context: Context, draft: String) = Intent(context, WorkspaceLaunchEditActivity::class.java)
            .putExtra(EXTRA_DRAFT, draft)
    }
}
