package dev.terminal.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import dev.terminal.app.ui.SessionInfoScreen
import kotlinx.coroutines.*
import org.json.JSONObject

/** RSS belongs to one session's tools, and never creates or owns that session. */
class SessionInfoActivity : RuntimeInformationActivity() {
    override val createServiceOnBind = false
    internal var rss by mutableStateOf<Long?>(null)
        private set
    private var loading by mutableStateOf(true)
    private var resumed = false
    private var job: Job? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        compose { SessionInfoScreen(intent.getStringExtra(Intent.EXTRA_TITLE).orEmpty(), rss, loading, { finish() }, ::refresh) }
    }
    override fun onResume() { super.onResume(); resumed = true; refresh() }
    override fun onPause() { resumed = false; job?.cancel(); super.onPause() }
    override fun runtimeInformationChanged() { if (resumed) refresh() }
    private fun refresh() {
        if (!resumed) return
        job?.cancel()
        job = lifecycleScope.launch {
            loading = true
            val service = runtime
            val id = intent.getStringExtra(EXTRA_SESSION_ID)
            rss = withContext(Dispatchers.IO) { runCatching {
                if (id == null || service == null) null else JSONObject(service.getSessionMemoryJson(id)).memoryBytes("rssBytes")
            }.getOrNull() }
            loading = false
        }
    }
    companion object {
        private const val EXTRA_SESSION_ID = "session_id"
        internal fun intent(context: Context, id: String, title: String) = Intent(context, SessionInfoActivity::class.java)
            .putExtra(EXTRA_SESSION_ID, id).putExtra(Intent.EXTRA_TITLE, title)
    }
}
