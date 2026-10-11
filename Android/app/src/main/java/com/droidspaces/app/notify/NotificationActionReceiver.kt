package com.droidspaces.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.droidspaces.app.MainActivity

/**
 * Receives the click / dismiss (swipe-away) intents attached to Linux-backed
 * notifications and relays them to [NotificationBridgeService], which owns
 * the live socket to the matching container's droidspaces-notifyd.
 *
 * Click also launches the app, same as any normal notification tap would -
 * the bridge relay is additive, not a replacement for that.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val containerName = intent.getStringExtra(EXTRA_CONTAINER) ?: return
        val bridgeId = intent.getIntExtra(EXTRA_BRIDGE_ID, -1)
        if (bridgeId < 0) return

        when (intent.action) {
            ACTION_CLICK -> {
                relay(context, containerName, NotificationBridgeService.Command.Click(bridgeId))
                val launch = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(launch)
            }
            ACTION_DISMISS -> {
                relay(context, containerName, NotificationBridgeService.Command.Dismiss(bridgeId))
            }
        }
    }

    private fun relay(
        context: Context,
        containerName: String,
        command: NotificationBridgeService.Command,
    ) {
        val serviceIntent = Intent(context, NotificationBridgeService::class.java).apply {
            action = NotificationBridgeService.ACTION_RELAY_COMMAND
            putExtra(NotificationBridgeService.EXTRA_CONTAINER, containerName)
            putExtra(NotificationBridgeService.EXTRA_BRIDGE_ID, command.bridgeId)
            putExtra(
                NotificationBridgeService.EXTRA_COMMAND,
                when (command) {
                    is NotificationBridgeService.Command.Click -> NotificationBridgeService.COMMAND_CLICK
                    is NotificationBridgeService.Command.Dismiss -> NotificationBridgeService.COMMAND_DISMISS
                },
            )
        }
        // Started (not bound): a dismiss can arrive while the app's UI is
        // fully backgrounded, and starting the service is what keeps the
        // process alive long enough to flush one small socket write, even
        // if NotificationBridgeService wasn't already running.
        ContextCompat.startForegroundService(context, serviceIntent)
    }

    companion object {
        const val ACTION_CLICK = "com.droidspaces.app.notify.ACTION_CLICK"
        const val ACTION_DISMISS = "com.droidspaces.app.notify.ACTION_DISMISS"
        const val EXTRA_CONTAINER = "container"
        const val EXTRA_BRIDGE_ID = "bridge_id"
    }
}
