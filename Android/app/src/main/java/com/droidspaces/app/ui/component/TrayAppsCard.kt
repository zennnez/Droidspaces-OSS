package com.droidspaces.app.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.droidspaces.app.R
import com.droidspaces.app.notify.NotifyBridgeMessage
import com.droidspaces.app.notify.TrayBridgeState

/**
 * "Droidspaces tray panel": running tray apps for one container (Syncthing,
 * qBittorrent, VPN clients, etc.), each with a status dot + subtitle and a
 * row of real actions pulled from the app's own dbusmenu - not a fixed
 * fake "pause/quit/settings" bar, since a button that silently does
 * nothing for apps that don't support it would be worse than no button.
 *
 * Rendered directly under a container's [RunningContainerCard] in the
 * Panel tab whenever that container has at least one tray item.
 */
@Composable
fun TrayAppsCard(
    items: List<TrayBridgeState.Item>,
    menus: Map<Int, List<NotifyBridgeMessage.TrayMenuEntry>>,
    onOpen: (Int) -> Unit,
    onRequestMenu: (Int) -> Unit,
    onMenuAction: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val context = LocalContext.current

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            Text(
                text = context.getString(R.string.tray_panel_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp),
            )

            items.forEachIndexed { index, item ->
                TrayAppRow(
                    item = item,
                    menu = menus[item.id],
                    onOpen = { onOpen(item.id) },
                    onRequestMenu = { onRequestMenu(item.id) },
                    onMenuAction = { actionId -> onMenuAction(item.id, actionId) },
                )
                if (index != items.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                    )
                }
            }
        }
    }
}

@Composable
private fun TrayAppRow(
    item: TrayBridgeState.Item,
    menu: List<NotifyBridgeMessage.TrayMenuEntry>?,
    onOpen: () -> Unit,
    onRequestMenu: () -> Unit,
    onMenuAction: (Int) -> Unit,
) {
    val context = LocalContext.current
    val dotColor = when (item.status) {
        "needs_attention" -> MaterialTheme.colorScheme.error
        "passive" -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        else -> Color(0xFF2E7D32) // active - green, independent of theme accent
    }
    val subtitle = item.tooltip.ifBlank {
        when (item.status) {
            "needs_attention" -> context.getString(R.string.tray_status_needs_attention)
            "passive" -> context.getString(R.string.tray_status_passive)
            else -> context.getString(R.string.tray_status_active)
        }
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Spacer(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier.padding(top = 4.dp, start = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = onOpen) {
                Text(context.getString(R.string.tray_action_open))
            }

            if (item.hasMenu) {
                if (menu == null) {
                    TextButton(onClick = onRequestMenu) {
                        Text(context.getString(R.string.tray_action_more))
                    }
                } else {
                    // Show up to 3 real, non-separator menu entries as quick
                    // action chips (this is where a user's actual "Pause",
                    // "Exit", "Settings" entries end up, whatever an app
                    // actually calls them - see the module writeup).
                    menu.filter { !it.separator }.take(3).forEach { entry ->
                        TextButton(
                            onClick = { onMenuAction(entry.actionId) },
                            enabled = entry.enabled,
                        ) {
                            Text(entry.label.ifBlank { "…" })
                        }
                    }
                }
            }
        }
    }
}
