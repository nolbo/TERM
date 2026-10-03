package dev.terminal.runtime.lifecycle

import dev.terminal.runtime.session.SessionLaunchSpec
import dev.terminal.runtime.session.SessionRuntime
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Service-owned creation requests; these records never own a PTY or a VM. */
internal class PendingAvfSessionLaunches {
    data class Launch(val requestId: String, val spec: SessionLaunchSpec, val error: String? = null) {
        val tabId get() = "avf-launch:$requestId"
    }

    private val launches = linkedMapOf<String, Launch>()

    @Synchronized fun begin(launch: Launch): Boolean {
        require(launch.spec.runtime == SessionRuntime.AVF)
        require(UUID.fromString(launch.requestId).toString() == launch.requestId)
        if (launch.requestId in launches) return false
        launches[launch.requestId] = launch
        return true
    }

    @Synchronized fun fail(launch: Launch, message: String) {
        launches[launch.requestId] = launch.copy(error = message)
    }

    @Synchronized fun complete(requestId: String) { launches.remove(requestId) }

    @Synchronized fun dismiss(tabId: String): Boolean {
        val launch = launches.values.firstOrNull { it.tabId == tabId && it.error != null } ?: return false
        launches.remove(launch.requestId)
        return true
    }

    @Synchronized fun project(sessionsJson: String): String {
        val source = JSONArray(sessionsJson)
        val result = JSONArray()
        val completed = mutableSetOf<String>()
        for (index in 0 until source.length()) {
            val session = source.getJSONObject(index)
            if (session.optBoolean("pendingLaunch")) continue
            result.put(session)
            session.optString("launchRequestId").takeIf { it.isNotBlank() }?.let(completed::add)
        }
        completed.forEach(launches::remove)
        launches.values.forEach { launch ->
            result.put(JSONObject().put("id", launch.tabId).put("runtime", "AVF")
                .put("title", launch.spec.profileName).put("initialTitle", launch.spec.profileName)
                .put("state", if (launch.error == null) "PREPARING" else "LAUNCH_FAILED")
                .put("pendingLaunch", true).put("launchRequestId", launch.requestId)
                .put("launchError", launch.error ?: JSONObject.NULL)
                .put("profileId", launch.spec.profileId).put("launchSpec", launch.spec.toJson()))
        }
        return result.toString()
    }
}
