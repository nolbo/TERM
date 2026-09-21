package dev.terminal.runtime.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import dev.terminal.runtime.R

/** App-owned notifications; PTY output cannot supply Android intents, actions or notification text. */
internal class SessionTaskNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val preferences = context.getSharedPreferences("runtime-settings", Context.MODE_PRIVATE)
    private val tracker = SessionActivityTracker(SystemClock::elapsedRealtime)
    private val lock = Any()
    @Volatile private var enabled = preferences.getBoolean(KEY_ENABLED, false)
    init {
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,
            context.getString(R.string.session_task_channel), NotificationManager.IMPORTANCE_DEFAULT))
        tracker.setEnabled(enabled)
    }
    fun setEnabled(value: Boolean) = synchronized(lock) {
        enabled = value
        preferences.edit().putBoolean(KEY_ENABLED, value).apply()
        tracker.setEnabled(value)
        if (!value) manager.activeNotifications.filter { it.notification.channelId == CHANNEL_ID }
            .forEach { manager.cancel(it.tag, it.id) }
    }
    fun input(id: String, bytes: ByteArray) = tracker.input(id, bytes)
    fun output(id: String, bytes: ByteArray, replay: Boolean, title: () -> String) =
        tracker.output(id, bytes, replay).forEach { post(it, title()) }
    fun exited(id: String, code: Int, title: String) = tracker.exited(id, code).forEach { post(it, title) }
    fun forget(id: String) { tracker.forget(id); manager.cancel(id, NOTIFICATION_ID) }

    private fun post(alert: SessionActivityTracker.Alert, title: String) = synchronized(lock) {
        if (!enabled || !manager.areNotificationsEnabled()) return@synchronized
        val intent = Intent().setClassName(context.packageName, "${context.packageName}.MainActivity")
            .setData(Uri.Builder().scheme("terminal").authority("session").appendPath(alert.sessionId).build())
            .putExtra(EXTRA_SESSION_ID, alert.sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val heading = context.getString(if (alert.kind == SessionActivityTracker.Kind.COMPLETED)
            R.string.session_task_completed else R.string.session_task_attention)
        val name = title.filter { !it.isISOControl() && it !in '\u202a'..'\u202e' && it !in '\u2066'..'\u2069' }.take(80)
        val text = if (alert.kind == SessionActivityTracker.Kind.COMPLETED && alert.exitCode != null)
            context.getString(R.string.session_task_exit, name, alert.exitCode)
            else context.getString(R.string.session_task_open, name)
        // Permission may be revoked between areNotificationsEnabled() and notify().
        try {
            manager.notify(alert.sessionId, NOTIFICATION_ID, Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_terminal)
                .setContentTitle(heading).setContentText(text).setContentIntent(pending)
                .setAutoCancel(true).setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setTimeoutAfter(86_400_000).build())
        } catch (_: SecurityException) { /* Android notification permission is optional. */ }
    }
    companion object {
        const val KEY_ENABLED = SessionNotificationSettings.KEY_ENABLED
        const val CHANNEL_ID = "session-tasks"
        const val NOTIFICATION_ID = 1002
        const val EXTRA_SESSION_ID = SessionNotificationSettings.EXTRA_SESSION_ID
    }
}
