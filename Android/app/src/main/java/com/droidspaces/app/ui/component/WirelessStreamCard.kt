package com.droidspaces.app.ui.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.droidspaces.app.R
import com.droidspaces.app.ui.util.LoadingIndicator
import com.droidspaces.app.ui.util.LoadingSize
import com.droidspaces.app.ui.util.rememberClearFocus
import com.droidspaces.app.ui.util.showError
import com.droidspaces.app.ui.util.showSuccess
import com.droidspaces.app.util.ContainerOpenRCManager
import com.droidspaces.app.util.ContainerSystemdManager
import com.droidspaces.app.util.WirelessDisplayManager
import com.droidspaces.app.util.WirelessStreamManager
import kotlinx.coroutines.launch

/**
 * Runtime status/control for the wireless display Stream backend. Shown
 * instead of [WirelessDisplayCard] when the container's wireless-display mode
 * is "stream". Unlike VNC there's no password step -- streaming has no
 * built-in authentication (see the card's own hint text and the script's
 * header comment for why), so enabling just needs capability detection to
 * pick an engine, then start.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WirelessStreamCard(
    containerName: String,
    isContainerRunning: Boolean,
    streamPort: Int,
    streamLanOnly: Boolean,
    streamFps: Int,
    streamBitrateKbps: Int,
    streamPreset: String,
    streamResolution: String,
    refreshTrigger: Int = 0,
    snackbarHostState: SnackbarHostState? = null,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clearFocus = rememberClearFocus()

    var status by remember { mutableStateOf<WirelessStreamManager.StreamStatus?>(null) }
    var caps by remember { mutableStateOf<WirelessStreamManager.Capabilities?>(null) }
    var isLoadingStatus by remember { mutableStateOf(true) }
    var connectionAddress by remember { mutableStateOf<String?>(null) }
    var isBusy by remember { mutableStateOf(false) }

    fun refreshStatus() {
        scope.launch {
            status = WirelessStreamManager.getStatus(containerName)
            caps = WirelessStreamManager.detectCapabilities(containerName)
            connectionAddress = if (isContainerRunning) {
                WirelessStreamManager.getConnectionAddress(containerName, streamPort, context)
            } else null
            isLoadingStatus = false
        }
    }

    LaunchedEffect(containerName, refreshTrigger) {
        if (status == null) {
            isLoadingStatus = true
        }
        val s = WirelessStreamManager.getStatus(containerName)
        val c = WirelessStreamManager.detectCapabilities(containerName)
        val addr = if (isContainerRunning) {
            WirelessStreamManager.getConnectionAddress(containerName, streamPort, context)
        } else null
        status = s
        caps = c
        connectionAddress = addr
        isLoadingStatus = false
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Devices, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    text = context.getString(R.string.stream_card_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (isLoadingStatus) {
                LoadingIndicator(size = LoadingSize.Medium)
            } else {
                val currentStatus = status
                val currentCaps = caps
                val engineLabel = when (currentStatus?.engine) {
                    "ffmpeg-hw" -> context.getString(R.string.stream_engine_ffmpeg_hw)
                    "gstreamer-sw" -> context.getString(R.string.stream_engine_gstreamer_sw)
                    "ffmpeg-sw" -> context.getString(R.string.stream_engine_ffmpeg_sw)
                    else -> ""
                }
                val isDark = isSystemInDarkTheme()
                val running = currentStatus?.running == true
                val badgeBg: Color
                val badgeFg: Color
                val dotColor: Color
                val badgeBorder: Color

                when {
                    currentStatus == null || !isContainerRunning -> {
                        badgeBg = MaterialTheme.colorScheme.surfaceVariant
                        badgeFg = MaterialTheme.colorScheme.onSurfaceVariant
                        dotColor = MaterialTheme.colorScheme.onSurfaceVariant
                        badgeBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    }
                    running -> {
                        badgeBg = if (isDark) Color(0xFF1B382B) else Color(0xFFE8F5E9)
                        badgeFg = if (isDark) Color(0xFF69F0AE) else Color(0xFF1B5E20)
                        dotColor = if (isDark) Color(0xFF69F0AE) else Color(0xFF2E7D32)
                        badgeBorder = if (isDark) Color(0xFF69F0AE).copy(alpha = 0.5f) else Color(0xFF2E7D32).copy(alpha = 0.4f)
                    }
                    currentStatus.installed -> {
                        badgeBg = if (isDark) Color(0xFF3E2D1A) else Color(0xFFFFF3E0)
                        badgeFg = if (isDark) Color(0xFFFFD54F) else Color(0xFFB76E00)
                        dotColor = if (isDark) Color(0xFFFFD54F) else Color(0xFFB76E00)
                        badgeBorder = if (isDark) Color(0xFFFFD54F).copy(alpha = 0.5f) else Color(0xFFB76E00).copy(alpha = 0.4f)
                    }
                    else -> {
                        badgeBg = MaterialTheme.colorScheme.surfaceVariant
                        badgeFg = MaterialTheme.colorScheme.onSurfaceVariant
                        dotColor = MaterialTheme.colorScheme.onSurfaceVariant
                        badgeBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = badgeBg,
                    border = BorderStroke(1.dp, badgeBorder)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        if (running) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(dotColor)
                            )
                        }
                        Text(
                            text = when {
                                currentStatus == null || !currentStatus.installed -> "Not Installed"
                                !isContainerRunning -> "Container Stopped"
                                running -> "Running"
                                else -> "Stopped"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = badgeFg
                        )
                    }
                }

                if (currentStatus?.running == true && !currentStatus.liveFps.isNullOrBlank()) {
                    Text(
                        text = context.getString(R.string.stream_live_stats, currentStatus.liveFps, currentStatus.liveBitrateKbps ?: "?"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }

                // Real, on-device capability detection -- never assumed.
                if (currentCaps != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = if (currentCaps.hwEncoderAvailable)
                                context.getString(R.string.stream_caps_hw_available, currentCaps.hwEncoderNode ?: "")
                            else
                                context.getString(R.string.stream_caps_hw_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }
                }

                if (currentStatus?.running == true) {
                    val anlandTag = if (currentStatus.anlandActive || currentCaps?.anlandActive == true) " • Anland Wayland" else ""
                    val displayStr = if (!currentStatus.display.isNullOrBlank()) "Display ${currentStatus.display}" else "Active Display"
                    val encStr = if (engineLabel.isNotBlank()) " ($engineLabel)" else ""
                    Text(
                        text = "$displayStr$encStr$anlandTag",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }

                if (currentStatus?.running == true) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = context.getString(R.string.stream_connection_address),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = connectionAddress ?: context.getString(R.string.stream_no_address_yet),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (connectionAddress != null) {
                                Text(
                                    text = context.getString(R.string.stream_connect_hint, connectionAddress),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                        if (connectionAddress != null) {
                            IconButton(onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Stream address", connectionAddress))
                                scope.launch { snackbarHostState?.showSuccess(context.getString(R.string.stream_address_copied)) }
                            }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null)
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    if (currentStatus?.running != true) {
                        Button(
                            onClick = {
                                clearFocus()
                                isBusy = true
                                scope.launch {
                                    val enableResult = WirelessStreamManager.enable(
                                        containerName, streamPort, streamLanOnly,
                                        streamFps, streamBitrateKbps, streamPreset, streamResolution
                                    )
                                    if (enableResult.isSuccess) {
                                        snackbarHostState?.showSuccess(context.getString(R.string.stream_enabled_success))
                                    } else {
                                        snackbarHostState?.showError(enableResult.message.ifBlank { "Failed to start streaming" })
                                    }
                                    refreshStatus()
                                    isBusy = false
                                }
                            },
                            enabled = !isBusy,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            if (isBusy) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            } else {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(context.getString(R.string.stream_enable_button), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    } else {
                        OutlinedButton(
                            onClick = {
                                clearFocus()
                                isBusy = true
                                scope.launch {
                                    val result = WirelessStreamManager.disable(containerName)
                                    if (result.isSuccess) {
                                        snackbarHostState?.showSuccess(context.getString(R.string.stream_disabled_success))
                                    } else {
                                        snackbarHostState?.showError(result.message.ifBlank { "Failed to disable" })
                                    }
                                    refreshStatus()
                                    isBusy = false
                                }
                            },
                            enabled = !isBusy,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(6.dp))
                            Text(context.getString(R.string.stream_disable))
                        }

                        OutlinedButton(
                            onClick = {
                                clearFocus()
                                isBusy = true
                                scope.launch {
                                    val result = WirelessStreamManager.restart(containerName)
                                    if (result.isSuccess) {
                                        snackbarHostState?.showSuccess(context.getString(R.string.stream_restarted_success))
                                    } else {
                                        snackbarHostState?.showError(result.message.ifBlank { "Failed to restart" })
                                    }
                                    refreshStatus()
                                    isBusy = false
                                }
                            },
                            enabled = !isBusy,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                            Spacer(Modifier.width(6.dp))
                            Text(context.getString(R.string.stream_restart))
                        }
                    }
                }
            }
        }
    }
}
