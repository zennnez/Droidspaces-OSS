package com.droidspaces.app.notify

import org.json.JSONObject

/**
 * Kotlin mirror of the wire protocol documented in
 * `src/include/notify_protocol.h`. Newline-delimited JSON, one object per
 * line, in both directions.
 */
sealed class NotifyBridgeMessage {

    data class Hello(val protoVersion: Int, val daemonPid: Int, val bootId: String) :
        NotifyBridgeMessage()

    data class Notify(
        val id: Int,
        val replacesId: Int,
        val appName: String,
        val summary: String,
        val body: String,
        val urgency: String,
        val icon: String,
        val timestamp: Long,
        val isUpdate: Boolean,
    ) : NotifyBridgeMessage()

    data class Close(val id: Int, val reason: String) : NotifyBridgeMessage()

    object Ping : NotifyBridgeMessage()

    // ---- tray bridge --------------------------------------------------

    data class TrayState(
        val protocol: String,
        val watcherOwner: String,
        val notifyFallback: Boolean,
    ) : NotifyBridgeMessage()

    data class TrayItem(
        val id: Int,
        val appId: String,
        val title: String,
        val status: String, // "active" | "passive" | "needs_attention"
        val iconName: String,
        val tooltip: String,
        val hasMenu: Boolean,
        val isUpdate: Boolean,
    ) : NotifyBridgeMessage()

    data class TrayRemove(val id: Int) : NotifyBridgeMessage()

    data class TrayMenuEntry(
        val actionId: Int,
        val label: String,
        val enabled: Boolean,
        val checked: Boolean,
        val separator: Boolean,
    )

    data class TrayMenu(val id: Int, val items: List<TrayMenuEntry>) : NotifyBridgeMessage()

    object Unknown : NotifyBridgeMessage()

    companion object {
        fun parse(line: String): NotifyBridgeMessage {
            return try {
                val obj = JSONObject(line)
                when (obj.optString("type")) {
                    "hello" -> Hello(
                        protoVersion = obj.optInt("proto_version", 1),
                        daemonPid = obj.optInt("daemon_pid", -1),
                        bootId = obj.optString("boot_id", ""),
                    )
                    "notify" -> Notify(
                        id = obj.getInt("id"),
                        replacesId = obj.optInt("replaces_id", 0),
                        appName = obj.optString("app_name", "Linux"),
                        summary = obj.optString("summary", ""),
                        body = obj.optString("body", ""),
                        urgency = obj.optString("urgency", "normal"),
                        icon = obj.optString("icon", ""),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis() / 1000),
                        isUpdate = obj.optBoolean("is_update", false),
                    )
                    "close" -> Close(
                        id = obj.getInt("id"),
                        reason = obj.optString("reason", "closed"),
                    )
                    "ping" -> Ping
                    "tray_state" -> TrayState(
                        protocol = obj.optString("protocol", "sni"),
                        watcherOwner = obj.optString("watcher_owner", "none"),
                        notifyFallback = obj.optBoolean("notify_fallback", false),
                    )
                    "tray_item", "tray_update" -> TrayItem(
                        id = obj.getInt("id"),
                        appId = obj.optString("app_id", ""),
                        title = obj.optString("title", ""),
                        status = obj.optString("status", "active"),
                        iconName = obj.optString("icon_name", ""),
                        tooltip = obj.optString("tooltip", ""),
                        hasMenu = obj.optBoolean("has_menu", false),
                        isUpdate = obj.optString("type") == "tray_update",
                    )
                    "tray_remove" -> TrayRemove(id = obj.getInt("id"))
                    "tray_menu" -> {
                        val itemsArr = obj.optJSONArray("items")
                        val entries = buildList {
                            if (itemsArr != null) {
                                for (i in 0 until itemsArr.length()) {
                                    val e = itemsArr.getJSONObject(i)
                                    add(
                                        TrayMenuEntry(
                                            actionId = e.optInt("action_id", -1),
                                            label = e.optString("label", ""),
                                            enabled = e.optBoolean("enabled", true),
                                            checked = e.optBoolean("checked", false),
                                            separator = e.optBoolean("separator", false),
                                        ),
                                    )
                                }
                            }
                        }
                        TrayMenu(id = obj.getInt("id"), items = entries)
                    }
                    else -> Unknown
                }
            } catch (_: Exception) {
                Unknown
            }
        }
    }
}

/** Outbound (app -> daemon) message builders. */
object NotifyBridgeOutbound {
    fun hello(deviceName: String): String =
        JSONObject().apply {
            put("type", "hello")
            put("proto_version", 1)
            put("device", deviceName)
        }.toString()

    fun pong(): String = JSONObject().apply { put("type", "pong") }.toString()

    fun dismiss(id: Int): String =
        JSONObject().apply {
            put("type", "dismiss")
            put("id", id)
        }.toString()

    fun click(id: Int, actionKey: String = "default"): String =
        JSONObject().apply {
            put("type", "click")
            put("id", id)
            put("action_key", actionKey)
        }.toString()

    fun trayActivate(id: Int, secondary: Boolean = false): String =
        JSONObject().apply {
            put("type", if (secondary) "tray_secondary_activate" else "tray_activate")
            put("id", id)
        }.toString()

    fun trayMenuRequest(id: Int): String =
        JSONObject().apply {
            put("type", "tray_menu_request")
            put("id", id)
        }.toString()

    fun trayMenuAction(id: Int, actionId: Int): String =
        JSONObject().apply {
            put("type", "tray_menu_action")
            put("id", id)
            put("action_id", actionId)
        }.toString()
}
