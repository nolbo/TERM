package dev.terminal.runtime.power

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/** Applies a bounded partial wake lock only when runtime policy and guest work require it. */
class RuntimeWakeController(context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val wakeLock = context.applicationContext
        .getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DroidTerminal:guest-work")
        .apply { setReferenceCounted(false) }
    private var holdRequested = false
    private val renewWakeLock = object : Runnable {
        override fun run() = synchronized(this@RuntimeWakeController) {
            if (!holdRequested) return@synchronized
            if (wakeLock.isHeld) wakeLock.release()
            wakeLock.acquire(MAX_HOLD_MS)
            handler.postDelayed(this, RENEW_AFTER_MS)
        }
    }

    @Synchronized
    fun update(policy: WakePolicy, guestWork: GuestWorkState) {
        val shouldHold = shouldHold(policy, guestWork)
        // Output can arrive many times per frame. Preserve the original renewal deadline.
        if (holdRequested == shouldHold && (!shouldHold || wakeLock.isHeld)) return
        holdRequested = shouldHold
        handler.removeCallbacks(renewWakeLock)
        if (shouldHold && !wakeLock.isHeld) wakeLock.acquire(MAX_HOLD_MS)
        if (!shouldHold && wakeLock.isHeld) wakeLock.release()
        if (shouldHold) handler.postDelayed(renewWakeLock, RENEW_AFTER_MS)
    }

    companion object {
        private const val MAX_HOLD_MS = 10 * 60 * 1_000L
        private const val RENEW_AFTER_MS = 9 * 60 * 1_000L
        fun shouldHold(policy: WakePolicy, guestWork: GuestWorkState): Boolean = when (policy) {
            WakePolicy.BATTERY_OPTIMIZED -> false
            WakePolicy.KEEP_ACTIVE_WORK_RUNNING -> guestWork == GuestWorkState.ACTIVE
            WakePolicy.ALWAYS_AWAKE -> guestWork != GuestWorkState.STOPPED
        }
    }

    @Synchronized
    fun release() {
        holdRequested = false
        handler.removeCallbacks(renewWakeLock)
        if (wakeLock.isHeld) wakeLock.release()
    }

    enum class WakePolicy { BATTERY_OPTIMIZED, KEEP_ACTIVE_WORK_RUNNING, ALWAYS_AWAKE }
    enum class GuestWorkState { STOPPED, IDLE, ACTIVE }
}
