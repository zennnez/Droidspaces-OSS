package com.droidspaces.app.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.droidspaces.app.R

/**
 * "Linux Notifications" settings section: an [Enabled] toggle plus a
 * per-application checklist, mirroring the daemon-side APPLICATIONS=
 * allow-list in notify-bridge.conf (see PreferencesManager.notifyBridgeApps
 * / notifyBridgeAppsConfigLine).
 *
 * [enabled] gates the whole card; the app list is only meaningful (and
 * only shown) once notifications are turned on.
 */
@Composable
fun LinuxNotificationsSettingsCard(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    apps: LinkedHashMap<String, Boolean>,
    onAppToggle: (String, Boolean) -> Unit,
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        color = if (darkTheme) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
    ) {
        Column {
            SwitchItem(
                icon = Icons.Default.Notifications,
                title = context.getString(R.string.settings_linux_notifications_enabled),
                summary = context.getString(R.string.settings_linux_notifications_subtitle),
                checked = enabled,
                onCheckedChange = onEnabledChange,
            )

            if (enabled) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                )

                Text(
                    text = context.getString(R.string.settings_linux_notifications_applications),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
                )
                Text(
                    text = context.getString(R.string.settings_linux_notifications_applications_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                )

                apps.forEach { (appName, appEnabled) ->
                    SwitchItem(
                        title = appName,
                        checked = appEnabled,
                        onCheckedChange = { checked -> onAppToggle(appName, checked) },
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                )
                Text(
                    text = context.getString(R.string.settings_linux_notifications_requires_host_net),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}
