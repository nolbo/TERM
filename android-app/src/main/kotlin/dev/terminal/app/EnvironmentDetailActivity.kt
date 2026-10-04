package dev.terminal.app

import android.content.Context
import android.content.Intent
import dev.terminal.runtime.session.EnvironmentId

class EnvironmentDetailActivity : EnvironmentScreenActivity() {
    override val titleResource: Int get() = R.string.storage_settings
    override fun selectedEnvironment() = EnvironmentId(requireNotNull(intent.getStringExtra("environmentId")))
    companion object {
        fun intent(context: Context, environmentId: String): Intent = Intent(context, EnvironmentDetailActivity::class.java)
            .putExtra("environmentId", environmentId)
    }
}
