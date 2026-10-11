package com.droidspaces.app.notify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Live tray state, updated by [NotificationBridgeService]'s per-container
 * connections and observed by the Control Panel's tray cards. Deliberately
 * in-memory only: on a fresh connection the daemon replays the full item
 * list (see notify_bridge.c's send_tray_dump_to), so there is nothing to
 * persist across an Android app restart - the truth always comes from the
 * daemon, never from local state.
 */
object TrayBridgeState {

    data class Item(
        val id: Int,
        val appId: String,
        val title: String,
        val status: String,
        val iconName: String,
        val tooltip: String,
        val hasMenu: Boolean,
    )

    data class ContainerTrayState(
        val watcherOwner: String = "none",
        val notifyFallback: Boolean = false,
        val items: List<Item> = emptyList(),
    )

    private val _byContainer = MutableStateFlow<Map<String, ContainerTrayState>>(emptyMap())
    val byContainer: StateFlow<Map<String, ContainerTrayState>> = _byContainer.asStateFlow()

    private val _menus = MutableStateFlow<Map<Pair<String, Int>, List<NotifyBridgeMessage.TrayMenuEntry>>>(
        emptyMap(),
    )
    val menus: StateFlow<Map<Pair<String, Int>, List<NotifyBridgeMessage.TrayMenuEntry>>> =
        _menus.asStateFlow()

    fun onTrayState(containerName: String, msg: NotifyBridgeMessage.TrayState) {
        _byContainer.update { map ->
            val current = map[containerName] ?: ContainerTrayState()
            map + (containerName to current.copy(
                watcherOwner = msg.watcherOwner,
                notifyFallback = msg.notifyFallback,
            ))
        }
    }

    fun onTrayItem(containerName: String, msg: NotifyBridgeMessage.TrayItem) {
        _byContainer.update { map ->
            val current = map[containerName] ?: ContainerTrayState()
            val newItem = Item(
                id = msg.id,
                appId = msg.appId,
                title = msg.title,
                status = msg.status,
                iconName = msg.iconName,
                tooltip = msg.tooltip,
                hasMenu = msg.hasMenu,
            )
            val items = current.items.filterNot { it.id == msg.id } + newItem
            map + (containerName to current.copy(items = items))
        }
    }

    fun onTrayRemove(containerName: String, id: Int) {
        _byContainer.update { map ->
            val current = map[containerName] ?: return@update map
            map + (containerName to current.copy(items = current.items.filterNot { it.id == id }))
        }
        _menus.update { it - (containerName to id) }
    }

    fun onTrayMenu(containerName: String, msg: NotifyBridgeMessage.TrayMenu) {
        _menus.update { it + ((containerName to msg.id) to msg.items) }
    }

    /** Clears everything for a container, e.g. when its bridge connection
     * is torn down (container stopped) so stale entries don't linger. */
    fun clearContainer(containerName: String) {
        _byContainer.update { it - containerName }
        _menus.update { map -> map.filterKeys { it.first != containerName } }
    }
}
