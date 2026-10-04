package dev.terminal.app

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.terminal.app.ui.UserspaceLinuxSettingsScreen
import dev.terminal.runtime.rootless.RootlessDnsPolicy
import dev.terminal.runtime.rootless.RootlessEngineInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Observes the engine; it owns no Environment, installation or PRoot session. */
class UserspaceLinuxSettingsActivity : RuntimeInformationActivity() {
    private val screen get() = intent.getStringExtra(SettingsDetailActivity.EXTRA_CATEGORY) ?: SettingsDetailActivity.ROOTLESS
    private var policy by mutableStateOf<RootlessDnsPolicy?>(null)
    private var engine by mutableStateOf<RootlessEngineInfo?>(null)
    private var unavailable by mutableStateOf(false)
    private var saving by mutableStateOf(false)
    private var saveFailed by mutableStateOf(false)
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        compose { UserspaceLinuxSettingsScreen(screen, policy, engine, unavailable, saving, saveFailed,
            intent.getIntExtra(SettingsDetailActivity.EXTRA_SECTION_INDEX, 0), { finish() }, { destination ->
                startActivity(Intent(this, UserspaceLinuxSettingsActivity::class.java)
                    .putExtra(SettingsDetailActivity.EXTRA_CATEGORY, destination))
            }, ::save) }
    }

    override fun runtimeInformationChanged() {
        loadJob?.cancel()
        val service = runtime
        if (service == null) {
            unavailable = true
            return
        }
        unavailable = false
        loadJob = lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) {
                JSONObject(service.userspaceLinuxSettingsJson)
            } }.onSuccess {
                policy = RootlessDnsPolicy.parse(it.getString("dnsMode"), it.getString("dnsServers"))
                engine = RootlessEngineInfo.fromJson(it.getJSONObject("engine"))
            }.onFailure {
                if (it is CancellationException) throw it
                policy = null
                engine = null
                unavailable = true
            }
        }
    }

    private fun save(value: RootlessDnsPolicy) {
        if (saving) return
        saving = true
        saveFailed = false
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) {
                val service = runtime ?: error("Runtime unavailable")
                check(service.setUserspaceLinuxDns(value.mode, value.servers.joinToString("\n")).isEmpty())
            } }
            saving = false
            result.onSuccess { policy = value; finish() }.onFailure {
                if (it is CancellationException) throw it
                saveFailed = true
            }
        }
    }

    override fun onStop() {
        loadJob?.cancel()
        super.onStop()
    }

    companion object {
        const val DNS = "rootless-dns"
        const val INFO = "rootless-info"
        const val LIMITATIONS = "rootless-limitations"
        fun owns(screen: String) = screen in setOf(SettingsDetailActivity.ROOTLESS, DNS, INFO, LIMITATIONS)
    }
}
