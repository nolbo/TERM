package dev.terminal.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.BatteryManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * The only host-facing Android API surface for guest bridge requests.
 * Guest code receives typed results only; it never receives a Binder, Context,
 * ContentResolver, or host file descriptor.
 */
class AndroidCapabilityBroker(
    context: Context,
    private val vmInstanceId: String,
    private val grants: CapabilityGrantStore = SharedPreferencesCapabilityGrantStore(context),
) {
    private val appContext = context.applicationContext

    init {
        appContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Linux guest notifications", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun clipboardGet(): BridgeResult<String> = withGrant(Capability.CLIPBOARD_READ) {
        val clipboard = appContext.getSystemService(ClipboardManager::class.java)
            ?: return@withGrant BridgeResult.Unavailable("Android clipboard service is unavailable")
        val clip = clipboard.primaryClip ?: return@withGrant BridgeResult.Success("")
        BridgeResult.Success(clip.getItemAt(0).coerceToText(appContext).toString())
    }

    fun clipboardSet(text: String): BridgeResult<Unit> = withGrant(Capability.CLIPBOARD_WRITE) {
        if (text.encodeToByteArray().size > MAX_CLIPBOARD_BYTES) {
            return@withGrant BridgeResult.Invalid("Clipboard text exceeds $MAX_CLIPBOARD_BYTES bytes")
        }
        val clipboard = appContext.getSystemService(ClipboardManager::class.java)
            ?: return@withGrant BridgeResult.Unavailable("Android clipboard service is unavailable")
        clipboard.setPrimaryClip(ClipData.newPlainText("TERM", text))
        BridgeResult.Success(Unit)
    }

    fun openUrl(rawUrl: String): BridgeResult<Unit> = withGrant(Capability.INTENT_OPEN_URL) {
        val uri = runCatching { Uri.parse(rawUrl) }.getOrElse {
            return@withGrant BridgeResult.Invalid("Invalid URL")
        }
        if (uri.scheme !in setOf("https", "http") || uri.host.isNullOrBlank()) {
            return@withGrant BridgeResult.Invalid("Only absolute HTTP(S) URLs are allowed")
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(appContext.packageManager) == null) {
            return@withGrant BridgeResult.Unavailable("No Android activity can open this URL")
        }
        appContext.startActivity(intent)
        BridgeResult.Success(Unit)
    }

    fun postNotification(title: String, text: String): BridgeResult<Unit> = withGrant(Capability.NOTIFICATION_POST) {
        if (title.length > MAX_NOTIFICATION_CHARS || text.length > MAX_NOTIFICATION_CHARS) {
            return@withGrant BridgeResult.Invalid("Notification title or text is too long")
        }
        val notifications = NotificationManagerCompat.from(appContext)
        if (!notifications.areNotificationsEnabled()) {
            return@withGrant BridgeResult.Unavailable("Android notifications are disabled")
        }
        notifications.notify(
            nextNotificationId(),
            NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_terminal)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .build(),
        )
        BridgeResult.Success(Unit)
    }

    fun batteryStatus(): BridgeResult<String> = withGrant(Capability.DEVICE_BATTERY) {
        val battery = appContext.getSystemService(BatteryManager::class.java)
            ?: return@withGrant BridgeResult.Unavailable("Android battery service is unavailable")
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        BridgeResult.Success("{\"percentage\":$level}")
    }

    private inline fun <T> withGrant(capability: Capability, action: () -> BridgeResult<T>): BridgeResult<T> = when (
        grants.get(vmInstanceId, capability)
    ) {
        CapabilityGrant.DENY -> BridgeResult.Denied(capability, "Capability is denied for this VM")
        CapabilityGrant.ASK -> BridgeResult.Denied(capability, "Capability requires host confirmation")
        CapabilityGrant.ALLOW_ONCE -> if (grants.consumeOnce(vmInstanceId, capability)) action()
        else BridgeResult.Denied(capability, "Capability grant was already consumed")
        CapabilityGrant.ALLOW_WHILE_VM_RUNNING, CapabilityGrant.ALLOW_ALWAYS -> action()
    }

    private fun nextNotificationId(): Int = (System.nanoTime() and Int.MAX_VALUE.toLong()).toInt()

    private companion object {
        const val CHANNEL_ID = "guest_bridge"
        const val MAX_CLIPBOARD_BYTES = 1_048_576
        const val MAX_NOTIFICATION_CHARS = 4_000
    }
}
