package dev.terminal.bridge

import android.content.Context

/** Private host-side persistence, namespaced by host-selected VM or Native identity. */
class SharedPreferencesCapabilityGrantStore(context: Context) : CapabilityGrantStore {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun get(vmInstanceId: String, capability: Capability): CapabilityGrant =
        preferences.getString(key(vmInstanceId, capability), CapabilityGrant.ASK.name)
            ?.let { runCatching { CapabilityGrant.valueOf(it) }.getOrDefault(CapabilityGrant.ASK) }
            ?: CapabilityGrant.ASK

    override fun set(vmInstanceId: String, capability: Capability, grant: CapabilityGrant) {
        preferences.edit().putString(key(vmInstanceId, capability), grant.name).apply()
    }

    override fun consumeOnce(vmInstanceId: String, capability: Capability): Boolean {
        val key = key(vmInstanceId, capability)
        if (preferences.getString(key, null) != CapabilityGrant.ALLOW_ONCE.name) return false
        preferences.edit().putString(key, CapabilityGrant.ASK.name).apply()
        return true
    }

    private fun key(vmInstanceId: String, capability: Capability): String = "grant.$vmInstanceId.${capability.wireName}"

    private companion object { const val FILE_NAME = "capability-grants" }
}
