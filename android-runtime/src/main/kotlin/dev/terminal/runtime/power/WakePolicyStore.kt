package dev.terminal.runtime.power

import android.content.Context

class WakePolicyStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("runtime-power", Context.MODE_PRIVATE)

    fun get(): RuntimeWakeController.WakePolicy = preferences.getString(KEY_POLICY, null)
        ?.let { runCatching { RuntimeWakeController.WakePolicy.valueOf(it) }.getOrNull() }
        ?: RuntimeWakeController.WakePolicy.BATTERY_OPTIMIZED

    fun set(policy: RuntimeWakeController.WakePolicy) {
        preferences.edit().putString(KEY_POLICY, policy.name).apply()
    }

    private companion object { const val KEY_POLICY = "wake-policy" }
}
