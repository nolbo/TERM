package dev.terminal.runtime.power

import android.content.Context

class WakePolicyStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("runtime-power", Context.MODE_PRIVATE)

    init {
        if (!preferences.getBoolean(KEY_PERSISTENT_DEFAULT, false)) {
            // Older settings screens persisted the battery-saving default even
            // when the user never selected a policy. Preserve explicit choices.
            val selected = context.applicationContext.getSharedPreferences("runtime-settings", Context.MODE_PRIVATE)
                .getString(KEY_POLICY, null)
            val policy = initialWakePolicy(preferences.getString(KEY_POLICY, null), selected)
            preferences.edit().putString(KEY_POLICY, policy.name)
                .putBoolean(KEY_PERSISTENT_DEFAULT, true).apply()
        }
    }

    fun get(): RuntimeWakeController.WakePolicy = preferences.getString(KEY_POLICY, null)
        ?.let { runCatching { RuntimeWakeController.WakePolicy.valueOf(it) }.getOrNull() }
        ?: DEFAULT_POLICY

    fun set(policy: RuntimeWakeController.WakePolicy) {
        preferences.edit().putString(KEY_POLICY, policy.name).apply()
    }

    companion object {
        val DEFAULT_POLICY = RuntimeWakeController.WakePolicy.ALWAYS_AWAKE
        private const val KEY_POLICY = "wake-policy"
        private const val KEY_PERSISTENT_DEFAULT = "persistent-session-default"
    }
}

internal fun initialWakePolicy(saved: String?, selected: String?): RuntimeWakeController.WakePolicy {
    fun parse(value: String?) = value?.let {
        runCatching { RuntimeWakeController.WakePolicy.valueOf(it) }.getOrNull()
    }
    return parse(selected) ?: parse(saved)?.takeUnless { it == RuntimeWakeController.WakePolicy.BATTERY_OPTIMIZED }
        ?: WakePolicyStore.DEFAULT_POLICY
}
