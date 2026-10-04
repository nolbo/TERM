package dev.terminal.app

import android.content.Context
import android.content.Intent
import dev.terminal.runtime.session.SessionRuntime
import dev.terminal.runtime.session.StorageRegistry
import dev.terminal.runtime.session.EnvironmentRegistry

class StorageDetailActivity : EnvironmentScreenActivity() {
    override val titleResource: Int get() = R.string.storage_settings
    override fun selectedEnvironment() = EnvironmentRegistry(filesDir).transaction(SessionProfiles(this@StorageDetailActivity).builtIns()) { forStorage(StorageRegistry.Key(
        requireNotNull(SessionRuntime.fromWireName(requireNotNull(intent.getStringExtra(EXTRA_RUNTIME)))),
        requireNotNull(intent.getStringExtra(EXTRA_STORAGE_ID)))).id }

    companion object {
        private const val EXTRA_RUNTIME = "runtime"
        private const val EXTRA_STORAGE_ID = "storageId"
        fun intent(context: Context, runtime: String, storageId: String): Intent =
            Intent(context, StorageDetailActivity::class.java)
                .putExtra(EXTRA_RUNTIME, runtime).putExtra(EXTRA_STORAGE_ID, storageId)
    }
}
