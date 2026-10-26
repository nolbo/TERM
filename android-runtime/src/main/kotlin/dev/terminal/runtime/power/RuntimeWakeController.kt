package dev.terminal.runtime.power

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/** Applies a bounded partial wake lock only when runtime policy and guest work require it. */
class RuntimeWakeController internal constructor(
    private val wakeLock: PowerManager.WakeLock,
    private val handler: Handler,
    private val maxHoldMillis: Long = MAX_HOLD_MS,
    private val renewAfterMillis: Long = RENEW_AFTER_MS,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DroidTerminal:guest-work"),
        Handler(Looper.getMainLooper()),
    )

    init {
        require(renewAfterMillis > 0 && maxHoldMillis > renewAfterMillis)
        wakeLock.setReferenceCounted(false)
    }
    private var holdRequested = false
    private val renewWakeLock = object : Runnable {
        override fun run() = synchronized(this@RuntimeWakeController) {
            if (!holdRequested) return@synchronized
            // Non-reference-counted acquire replaces the timeout while retaining
            // protection. Releasing first permits screen-off suspend between calls.
            wakeLock.acquire(maxHoldMillis)
            dev.terminal.runtime.lifecycle.ConnectionDiagnostics.record("power.wake.renew",
                details = mapOf("held" to wakeLock.isHeld.toString(), "timeout_ms" to maxHoldMillis.toString()))
            handler.postDelayed(this, renewAfterMillis)
        }
    }

    @Synchronized
    fun update(policy: WakePolicy, guestWork: GuestWorkState) {
        val shouldHold = shouldHold(policy, guestWork)
        // Output can arrive many times per frame. Preserve the original renewal deadline.
        if (holdRequested == shouldHold && (!shouldHold || wakeLock.isHeld)) return
        holdRequested = shouldHold
        handler.removeCallbacks(renewWakeLock)
        if (shouldHold && !wakeLock.isHeld) wakeLock.acquire(maxHoldMillis)
        if (!shouldHold && wakeLock.isHeld) wakeLock.release()
        if (shouldHold) handler.postDelayed(renewWakeLock, renewAfterMillis)
        dev.terminal.runtime.lifecycle.ConnectionDiagnostics.record(if (shouldHold) "power.wake.acquire" else "power.wake.release",
            code = policy.name, details = mapOf("work" to guestWork.name, "held" to wakeLock.isHeld.toString(),
                "timeout_ms" to maxHoldMillis.toString()))
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
        val wasHeld = wakeLock.isHeld || holdRequested
        holdRequested = false
        handler.removeCallbacks(renewWakeLock)
        if (wakeLock.isHeld) wakeLock.release()
        if (wasHeld) dev.terminal.runtime.lifecycle.ConnectionDiagnostics.record("power.wake.release", code = "explicit")
    }

    enum class WakePolicy { BATTERY_OPTIMIZED, KEEP_ACTIVE_WORK_RUNNING, ALWAYS_AWAKE }
    enum class GuestWorkState { STOPPED, IDLE, ACTIVE }
}
