package com.droidspaces.app.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.droidspaces.app.R

/**
 * "Tray Apps" settings section: enable toggle + TRAY_MODE selector
 * (android/xfce/both), mirroring the daemon-side TRAY_BRIDGE/TRAY_MODE
 * keys in notify-bridge.conf.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrayBridgeSettingsCard(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    mode: String,
    onModeChange: (String) -> Unit,
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val modes = listOf(
        "android" to context.getString(R.string.settings_tray_bridge_mode_android),
        "xfce" to context.getString(R.string.settings_tray_bridge_mode_xfce),
        "both" to context.getString(R.string.settings_tray_bridge_mode_both),
    )

    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        color = if (darkTheme) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
    ) {
        Column {
            SwitchItem(
                icon = Icons.Default.Apps,
                title = context.getString(R.string.settings_tray_bridge_enabled),
                summary = context.getString(R.string.settings_tray_bridge_subtitle),
                checked = enabled,
                onCheckedChange = onEnabledChange,
            )

            if (enabled) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                )

                Text(
                    text = context.getString(R.string.settings_tray_bridge_mode),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
                )

                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    modes.forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                            onClick = { onModeChange(value) },
                            selected = mode == value,
                        ) {
                            Text(label, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                Text(
                    text = context.getString(R.string.settings_tray_bridge_mode_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(
                        PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    ),
                )
            }
        }
    }
}
