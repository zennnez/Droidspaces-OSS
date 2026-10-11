package com.droidspaces.app.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.droidspaces.app.MainActivity
import com.droidspaces.app.R
import com.droidspaces.app.util.ContainerManager
import com.droidspaces.app.util.Constants
import com.droidspaces.app.util.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Owns one live socket per monitored container, talking the NDJSON
 * protocol from `notify_protocol.h` to that container's
 * `droidspaces-notifyd`. Responsibilities map directly to the feature
 * requirements:
 *
 *  - "Detect Android app lifecycle": runs as a foreground service so it
 *    keeps receiving notifications while the app UI is backgrounded or
 *    swiped away, and shuts itself down (stopSelf) once the user disables
 *    the bridge in Settings or no container is eligible anymore.
 *  - "Handle container restart" / "Handle Linux notification daemon
 *    restart": each connection reconnects with backoff on any socket
 *    error, and compares the daemon's `boot_id` (from "hello") against
 *    the last one seen for that container - a changed boot_id means the
 *    notify daemon restarted, so any in-flight id assumptions are reset
 *    even though already-shown Android notifications are left alone.
 *  - "Do not duplicate notifications endlessly": all creation/update
 *    logic lives in LinuxNotificationManager, keyed by stable
 *    (container, bridge id) pairs - reconnecting never re-creates
 *    already-shown notifications from scratch.
 */
class NotificationBridgeService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var notifyManager: LinuxNotificationManager
    private lateinit var prefs: PreferencesManager

    private val connections = HashMap<String, ContainerConnection>()
    private var pollJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        notifyManager = LinuxNotificationManager(this)
        prefs = PreferencesManager.getInstance(this)
        createServiceChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()

        when (intent?.action) {
            ACTION_RELAY_COMMAND -> handleRelayCommand(intent)
            ACTION_STOP -> {
                stopAllAndSelf()
                return START_NOT_STICKY
            }
            else -> ensurePolling()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pollJob?.cancel()
        connections.values.forEach { it.stop() }
        connections.clear()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ---- discovery: which containers should be monitored -----------------

    /**
     * Polls the container list every [POLL_INTERVAL_MS]. There is no single
     * "container started/stopped" callback wired in yet (see the module
     * writeup), so polling is the robust fallback: it converges within one
     * interval on container start, container stop, and the bridge being
     * toggled on/off in Settings, without needing a call-site change in
     * every place a container can be started.
     */
    private fun ensurePolling() {
        if (pollJob?.isActive == true) return
        pollJob = serviceScope.launch {
            while (isActive) {
                if (!prefs.isNotifyBridgeEnabled) {
                    connections.values.forEach { it.stop() }
                    connections.clear()
                    stopAllAndSelf()
                    return@launch
                }
                reconcileConnections()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun reconcileConnections() {
        val running = try {
            ContainerManager.listContainers().filter { it.pid != null && it.pid != 0 }
        } catch (_: Exception) {
            emptyList()
        }

        val eligible = running.filter { it.netMode == "host" }
        val eligibleNames = eligible.map { it.name }.toSet()

        // Stop connections for containers no longer running/eligible.
        val toRemove = connections.keys - eligibleNames
        toRemove.forEach { name ->
            connections.remove(name)?.stop()
            TrayBridgeState.clearContainer(name)
            notifyManager.clearTraySummary(name)
        }

        // Start connections for newly-eligible containers.
        for (container in eligible) {
            if (!connections.containsKey(container.name)) {
                val conn = ContainerConnection(
                    containerName = container.name,
                    host = "127.0.0.1",
                    port = Constants.NOTIFY_BRIDGE_DEFAULT_PORT,
                    notifyManager = notifyManager,
                    prefs = prefs,
                    scope = serviceScope,
                )
                connections[container.name] = conn
                conn.start()
            }
        }

        if (connections.isEmpty()) {
            updateForegroundNotification(idle = true)
        } else {
            updateForegroundNotification(idle = false)
        }
    }

    private fun handleRelayCommand(intent: Intent) {
        val containerName = intent.getStringExtra(EXTRA_CONTAINER) ?: return
        val bridgeId = intent.getIntExtra(EXTRA_BRIDGE_ID, -1)
        if (bridgeId < 0) return
        val conn = connections[containerName] ?: run {
            // Service (or just this container's connection) wasn't already
            // running - e.g. the notification survived a service restart.
            // We don't have a live socket to relay over; this is a known
            // limitation documented in the writeup (Android-initiated
            // dismiss/click while fully disconnected is a no-op on the
            // Linux side, though the Android notification itself still
            // clears normally).
            return
        }
        when (intent.getStringExtra(EXTRA_COMMAND)) {
            COMMAND_DISMISS -> conn.sendDismiss(bridgeId)
            COMMAND_CLICK -> conn.sendClick(bridgeId)
            COMMAND_TRAY_ACTIVATE -> conn.sendTrayActivate(bridgeId, secondary = false)
            COMMAND_TRAY_SECONDARY_ACTIVATE -> conn.sendTrayActivate(bridgeId, secondary = true)
            COMMAND_TRAY_MENU_REQUEST -> conn.sendTrayMenuRequest(bridgeId)
            COMMAND_TRAY_MENU_ACTION -> {
                val actionId = intent.getIntExtra(EXTRA_ACTION_ID, -1)
                if (actionId >= 0) conn.sendTrayMenuAction(bridgeId, actionId)
            }
        }
    }

    private fun stopAllAndSelf() {
        connections.values.forEach { it.stop() }
        connections.clear()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    // ---- foreground notification ------------------------------------------

    private fun createServiceChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(SERVICE_CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        SERVICE_CHANNEL_ID,
                        getString(R.string.notify_bridge_service_channel_name),
                        NotificationManager.IMPORTANCE_MIN,
                    ),
                )
            }
        }
    }

    private fun buildServiceNotification(idle: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (idle) {
            getString(R.string.notify_bridge_service_idle)
        } else {
            resources.getQuantityString(
                R.plurals.notify_bridge_service_active,
                connections.size, connections.size,
            )
        }
        return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setContentTitle(getString(R.string.notify_bridge_service_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_shortcut_containers)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun startForegroundCompat() {
        val notification = buildServiceNotification(idle = connections.isEmpty())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                SERVICE_NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(SERVICE_NOTIFICATION_ID, notification)
        }
    }

    private fun updateForegroundNotification(idle: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(SERVICE_NOTIFICATION_ID, buildServiceNotification(idle))
    }

    sealed class Command(val bridgeId: Int) {
        class Dismiss(bridgeId: Int) : Command(bridgeId)
        class Click(bridgeId: Int) : Command(bridgeId)
    }

    companion object {
        const val ACTION_RELAY_COMMAND = "com.droidspaces.app.notify.RELAY_COMMAND"
        const val ACTION_STOP = "com.droidspaces.app.notify.STOP"
        const val EXTRA_CONTAINER = "container"
        const val EXTRA_BRIDGE_ID = "bridge_id"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_ACTION_ID = "action_id"
        const val COMMAND_DISMISS = "dismiss"
        const val COMMAND_CLICK = "click"
        const val COMMAND_TRAY_ACTIVATE = "tray_activate"
        const val COMMAND_TRAY_SECONDARY_ACTIVATE = "tray_secondary_activate"
        const val COMMAND_TRAY_MENU_REQUEST = "tray_menu_request"
        const val COMMAND_TRAY_MENU_ACTION = "tray_menu_action"

        private const val SERVICE_CHANNEL_ID = "droidspaces_notify_bridge_service"
        private const val SERVICE_NOTIFICATION_ID = 0x4E00_0001
        private const val POLL_INTERVAL_MS = 15_000L
    }
}

/**
 * One reconnecting NDJSON socket connection to a single container's
 * droidspaces-notifyd. Backoff is capped the same way the daemon side
 * caps its own D-Bus reconnect (see notify_bridge.c), for the same
 * reason: bounded retry cost if the daemon is simply not installed/
 * running in that container.
 */
private class ContainerConnection(
    private val containerName: String,
    private val host: String,
    private val port: Int,
    private val notifyManager: LinuxNotificationManager,
    private val prefs: PreferencesManager,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null
    private val outbox = Channel<String>(capacity = 16)
    @Volatile private var socketOut: OutputStream? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { runLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        socketOut = null
    }

    fun sendDismiss(bridgeId: Int) {
        outbox.trySend(NotifyBridgeOutbound.dismiss(bridgeId))
    }

    fun sendClick(bridgeId: Int) {
        outbox.trySend(NotifyBridgeOutbound.click(bridgeId))
    }

    fun sendTrayActivate(bridgeId: Int, secondary: Boolean) {
        outbox.trySend(NotifyBridgeOutbound.trayActivate(bridgeId, secondary))
    }

    fun sendTrayMenuRequest(bridgeId: Int) {
        outbox.trySend(NotifyBridgeOutbound.trayMenuRequest(bridgeId))
    }

    fun sendTrayMenuAction(bridgeId: Int, actionId: Int) {
        outbox.trySend(NotifyBridgeOutbound.trayMenuAction(bridgeId, actionId))
    }

    private suspend fun runLoop() {
        var backoffMs = 1000L
        while (scope.isActive) {
            try {
                connectOnce()
                backoffMs = 1000L // reset after a clean connection
            } catch (_: IOException) {
                // fall through to backoff below
            } catch (_: SocketTimeoutException) {
                // fall through to backoff below
            }
            socketOut = null
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private suspend fun connectOnce() {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            val out = socket.getOutputStream()
            socketOut = out
            write(out, NotifyBridgeOutbound.hello(deviceName()))

            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

            // Drain any queued outbound commands (dismiss/click) that piled
            // up while disconnected, alongside reading inbound lines. A
            // dedicated writer coroutine keeps this simple without needing
            // a second thread per connection.
            val writer = scope.launch {
                for (msg in outbox) {
                    try {
                        write(out, msg)
                    } catch (_: IOException) {
                        break
                    }
                }
            }

            try {
                while (scope.isActive) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    handleLine(line)
                }
            } finally {
                writer.cancel()
            }
        }
    }

    private fun handleLine(line: String) {
        when (val msg = NotifyBridgeMessage.parse(line)) {
            is NotifyBridgeMessage.Hello -> {
                val previous = prefs.lastKnownBootId(containerName)
                if (previous != null && previous != msg.bootId) {
                    // "Handle Linux notification daemon restart": nothing
                    // destructive to do here - already-shown notifications
                    // stay as-is, we just know old bridge ids from before
                    // this boot_id are no longer resolvable to a live
                    // D-Bus id on the daemon side (dismiss/click for them
                    // will simply no-op there, which is the documented
                    // limitation, not a crash).
                }
                prefs.setLastKnownBootId(containerName, msg.bootId)
            }
            is NotifyBridgeMessage.Notify -> {
                notifyManager.show(containerName, msg)
            }
            is NotifyBridgeMessage.Close -> {
                notifyManager.cancel(containerName, msg.id)
            }
            NotifyBridgeMessage.Ping -> {
                outbox.trySend(NotifyBridgeOutbound.pong())
            }
            is NotifyBridgeMessage.TrayState -> {
                TrayBridgeState.onTrayState(containerName, msg)
                refreshTraySummary()
            }
            is NotifyBridgeMessage.TrayItem -> {
                TrayBridgeState.onTrayItem(containerName, msg)
                refreshTraySummary()
            }
            is NotifyBridgeMessage.TrayRemove -> {
                TrayBridgeState.onTrayRemove(containerName, msg.id)
                refreshTraySummary()
            }
            is NotifyBridgeMessage.TrayMenu -> {
                TrayBridgeState.onTrayMenu(containerName, msg)
            }
            NotifyBridgeMessage.Unknown -> {
                // forward-compatible: ignore message types this build
                // doesn't know about yet.
            }
        }
    }

    /**
     * Requirement: "Integrate with Android notifications where native tray
     * is impossible" (TRAY_MODE=both). The Droidspaces app itself has no
     * persistent on-screen surface when backgrounded, so a notification-
     * shade summary is the closest Android equivalent to an always-visible
     * desktop tray - updated in place (not re-posted) as items change.
     */
    private fun refreshTraySummary() {
        val state = TrayBridgeState.byContainer.value[containerName] ?: return
        if (state.notifyFallback && state.items.isNotEmpty()) {
            notifyManager.updateTraySummary(containerName, state.items)
        } else {
            notifyManager.clearTraySummary(containerName)
        }
    }

    private fun write(out: OutputStream, line: String) {
        out.write((line + "\n").toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun deviceName(): String = Build.MODEL ?: "Android"

    companion object {
        private const val CONNECT_TIMEOUT_MS = 4000
        private const val READ_TIMEOUT_MS = 45_000
        private const val MAX_BACKOFF_MS = 30_000L
    }
}
