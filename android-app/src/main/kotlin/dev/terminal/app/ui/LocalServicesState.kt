package dev.terminal.app.ui

import dev.terminal.runtime.forwarding.LocalService
import org.json.JSONObject

internal data class ServiceEnvironment(val id: String, val name: String, val runtime: String)
internal data class LocalServicesSnapshot(val services: List<LocalService>, val environments: List<ServiceEnvironment>,
    val links: Map<String, String>, val storageError: String?) {
    companion object {
        fun parse(encoded: String): LocalServicesSnapshot {
            require(encoded.length <= 1_048_576)
            val root = JSONObject(encoded)
            val rows = root.getJSONArray("services")
            require(rows.length() <= 512)
            val environments = root.getJSONArray("environments")
            val links = root.getJSONObject("links")
            return LocalServicesSnapshot(List(rows.length()) { LocalService.fromJson(rows.getJSONObject(it)) },
                List(environments.length()) { environments.getJSONObject(it).let { row ->
                    ServiceEnvironment(row.getString("id"), row.getString("name"), row.getString("runtime"))
                } }, links.keys().asSequence().associateWith { links.getString(it) },
                if (root.isNull("storageError")) null else root.getString("storageError"))
        }
    }
}
