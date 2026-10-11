package com.droidspaces.app.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.droidspaces.app.R
import com.droidspaces.app.util.Constants

/**
 * Turns [NotifyBridgeMessage.Notify] / [NotifyBridgeMessage.Close] events into
 * real Android notifications, and keeps enough state around to satisfy:
 *
 *  - "Do not duplicate notifications endlessly": every (containerName,
 *    bridge id) pair maps to exactly one Android notification id, so a
 *    replayed/duplicate "notify" for the same id updates in place via
 *    NotificationManager.notify() instead of stacking a new one.
 *  - "Handle container restart" / "Handle Linux notification daemon
 *    restart": the mapping is keyed by (containerName, bridgeId), not by
 *    anything that resets across daemon restarts, so notifications already
 *    on screen survive a reconnect. The daemon's boot_id is used
 *    separately (see NotificationBridgeService) purely to decide whether
 *    to trust "dismiss" replay for old ids, not to recreate this map.
 */
class LinuxNotificationManager(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val compat = NotificationManagerCompat.from(context)

    init {
        ensureChannel()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (notificationManager.getNotificationChannel(Constants.NOTIFY_BRIDGE_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    Constants.NOTIFY_BRIDGE_CHANNEL_ID,
                    context.getString(R.string.linux_notifications_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.linux_notifications_channel_description)
                }
                notificationManager.createNotificationChannel(channel)
            }
            if (notificationManager.getNotificationChannel(Constants.TRAY_BRIDGE_SUMMARY_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    Constants.TRAY_BRIDGE_SUMMARY_CHANNEL_ID,
                    context.getString(R.string.tray_summary_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.tray_summary_channel_description)
                }
                notificationManager.createNotificationChannel(channel)
            }
        }
    }

    /** Stable Android notification id for a given container + bridge id. */
    private fun androidNotifId(containerName: String, bridgeId: Int): Int {
        // Namespace by container so two containers' bridge ids (which both
        // start at 1) can't collide, and stay clear of ids used elsewhere
        // in the app (terminal foreground notification, etc.) by using a
        // dedicated high range.
        val containerHash = containerName.hashCode() and 0x0FFF
        return 0x4E00_0000 or (containerHash shl 12) or (bridgeId and 0x0FFF)
    }

    fun show(containerName: String, msg: NotifyBridgeMessage.Notify) {
        val urgencyPriority = when (msg.urgency) {
            "critical" -> NotificationCompat.PRIORITY_HIGH
            "low" -> NotificationCompat.PRIORITY_LOW
            else -> NotificationCompat.PRIORITY_DEFAULT
        }

        val notifId = androidNotifId(containerName, msg.id)

        val clickIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_CLICK
            putExtra(NotificationActionReceiver.EXTRA_CONTAINER, containerName)
            putExtra(NotificationActionReceiver.EXTRA_BRIDGE_ID, msg.id)
        }
        val clickPendingIntent = PendingIntent.getBroadcast(
            context, notifId, clickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val deleteIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_DISMISS
            putExtra(NotificationActionReceiver.EXTRA_CONTAINER, containerName)
            putExtra(NotificationActionReceiver.EXTRA_BRIDGE_ID, msg.id)
        }
        val deletePendingIntent = PendingIntent.getBroadcast(
            context, notifId + 1, deleteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, Constants.NOTIFY_BRIDGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shortcut_containers)
            .setContentTitle(msg.summary.ifBlank { msg.appName })
            .setContentText(msg.body)
            .setSubText(msg.appName)
            .setWhen(msg.timestamp * 1000L)
            .setShowWhen(true)
            .setPriority(urgencyPriority)
            .setAutoCancel(true)
            .setContentIntent(clickPendingIntent)
            .setDeleteIntent(deletePendingIntent)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup("droidspaces_linux_notify_$containerName")

        if (msg.body.length > 40) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(msg.body))
        }

        compat.notify(notifId, builder.build())
    }

    fun cancel(containerName: String, bridgeId: Int) {
        compat.cancel(androidNotifId(containerName, bridgeId))
    }

    /** Clears every Linux notification shown for a container, e.g. when the
     * user disables the bridge or the container is uninstalled. */
    fun cancelAllForContainer(containerName: String) {
        // We don't track the full set of currently-shown ids in memory
        // (the mapping is pure-function of containerName+bridgeId), so
        // callers that need a full sweep should track ids they've shown;
        // for the common "toggle off" case, notifications simply stop
        // being posted and age out naturally, or the user clears them.
        // Exposed for completeness / future StatusBarNotification-based
        // sweep (NotificationManager.getActiveNotifications(), API 23+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val prefix = androidNotifId(containerName, 0) and 0x4FFF_F000.toInt()
            notificationManager.activeNotifications
                .filter { (it.id and 0x4FFF_F000.toInt()) == prefix }
                .forEach { notificationManager.cancel(it.id) }
        }
    }

    // ---- tray bridge notification-shade fallback (TRAY_MODE=both) ---------

    private fun traySummaryNotifId(containerName: String): Int =
        0x5A00_0000 or (containerName.hashCode() and 0x00FF_FFFF)

    /**
     * Requirement: "Integrate with Android notifications where native tray
     * is impossible." One low-priority, silent, ongoing notification per
     * container listing its running tray apps - the closest Android
     * equivalent to an always-visible desktop tray when the Droidspaces
     * app itself isn't on screen. Updated in place (same id) as tray state
     * changes, never re-posted/re-alerted.
     */
    fun updateTraySummary(containerName: String, items: List<TrayBridgeState.Item>) {
        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, com.droidspaces.app.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val style = NotificationCompat.InboxStyle()
            .setBigContentTitle(
                context.resources.getQuantityString(
                    R.plurals.tray_summary_title, items.size, items.size,
                ),
            )
        items.take(6).forEach { item ->
            val statusLabel = when (item.status) {
                "needs_attention" -> context.getString(R.string.tray_status_needs_attention)
                "passive" -> context.getString(R.string.tray_status_passive)
                else -> item.tooltip.ifBlank { context.getString(R.string.tray_status_active) }
            }
            style.addLine("${item.title}: $statusLabel")
        }

        val builder = NotificationCompat.Builder(context, Constants.TRAY_BRIDGE_SUMMARY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shortcut_containers)
            .setContentTitle(
                context.resources.getQuantityString(
                    R.plurals.tray_summary_title, items.size, items.size,
                ),
            )
            .setContentText(items.joinToString(", ") { it.title })
            .setStyle(style)
            .setSubText(containerName)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .setCategory(NotificationCompat.CATEGORY_STATUS)

        compat.notify(traySummaryNotifId(containerName), builder.build())
    }

    fun clearTraySummary(containerName: String) {
        compat.cancel(traySummaryNotifId(containerName))
    }
}
