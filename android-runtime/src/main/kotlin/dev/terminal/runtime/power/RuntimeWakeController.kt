package dev.terminal.runtime.power

import android.content.Context
import android.os.PowerManager

/** Applies a bounded partial wake lock only when runtime policy and guest work require it. */
class RuntimeWakeController(context: Context) {
    private val wakeLock = context.applicationContext
        .getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DroidTerminal:guest-work")

    fun update(policy: WakePolicy, guestWork: GuestWorkState) {
        val shouldHold = when (policy) {
            WakePolicy.BATTERY_OPTIMIZED -> false
            WakePolicy.KEEP_ACTIVE_WORK_RUNNING -> guestWork == GuestWorkState.ACTIVE
            WakePolicy.ALWAYS_AWAKE -> true
        }
        if (shouldHold && !wakeLock.isHeld) wakeLock.acquire(MAX_HOLD_MS)
        if (!shouldHold && wakeLock.isHeld) wakeLock.release()
    }

    fun release() { if (wakeLock.isHeld) wakeLock.release() }

    enum class WakePolicy { BATTERY_OPTIMIZED, KEEP_ACTIVE_WORK_RUNNING, ALWAYS_AWAKE }
    enum class GuestWorkState { IDLE, ACTIVE }

    private companion object { const val MAX_HOLD_MS = 10 * 60 * 1_000L }
}
