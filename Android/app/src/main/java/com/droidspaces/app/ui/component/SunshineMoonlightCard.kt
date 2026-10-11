package com.droidspaces.app.ui.component

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidspaces.app.R
import com.droidspaces.app.util.SunshineManager
import kotlinx.coroutines.launch

private val MonoFont = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold)
)

/**
 * Management card for Sunshine (Moonlight Game & Desktop Streaming).
 */
@Composable
fun SunshineMoonlightCard(
    containerName: String,
    isContainerRunning: Boolean,
    onViewLogs: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    snackbarHostState: SnackbarHostState? = null,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var status by remember { mutableStateOf<SunshineManager.SunshineStatus?>(null) }
    var connectionIp by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isBusy by remember { mutableStateOf(false) }
    var showCredsDialog by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }

    var adminUser by remember { mutableStateOf("admin") }
    var adminPass by remember { mutableStateOf("admin") }

    fun refreshStatus() {
        scope.launch {
            SunshineManager.initialize(context)
            val s = SunshineManager.getStatus(containerName)
            val ip = if (isContainerRunning) SunshineManager.getConnectionIp(containerName, context) else null
            status = s
            connectionIp = ip
            isLoading = false
        }
    }

    LaunchedEffect(containerName, isContainerRunning) {
        refreshStatus()
    }

    val isDark = isSystemInDarkTheme()
    val currentStatus = status
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
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        Icons.Default.SportsEsports,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Sunshine (Moonlight Stream)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Status Badge
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = badgeBg,
                    border = BorderStroke(1.dp, badgeBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                        Text(
                            text = when {
                                currentStatus == null || !isContainerRunning -> "Inactive"
                                running -> "Streaming Ready"
                                currentStatus.installed -> "Stopped"
                                else -> "Not Installed"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = badgeFg
                        )
                    }
                }
            }

            Text(
                text = "Stream your container desktop to Moonlight clients (Android, PC, TV, Deck, iOS) with ultra-low latency, audio, and gamepad support.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (isLoading) {
                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            } else if (currentStatus?.installed != true) {
                // Not Installed state
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Sunshine server is not installed in this container rootfs.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    isBusy = true
                                    val res = SunshineManager.installSunshine(containerName)
                                    if (res.isSuccess) {
                                        snackbarHostState?.showSnackbar("Sunshine installed successfully")
                                    } else {
                                        snackbarHostState?.showSnackbar("Installation failed: ${res.message}")
                                    }
                                    refreshStatus()
                                    onRefresh?.invoke()
                                    isBusy = false
                                }
                            },
                            enabled = !isBusy && isContainerRunning,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isBusy) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary)
                                Spacer(Modifier.width(8.dp))
                            }
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Install Sunshine Server")
                        }
                    }
                }
            } else {
                // Installed state: Details & Controls
                val ip = connectionIp ?: "127.0.0.1"
                val webUrl = "https://$ip:${currentStatus.webPort}"

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Web UI Address
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Sunshine Web Admin & Pairing UI",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SelectionContainer {
                                    Text(
                                        text = webUrl,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFont),
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    IconButton(
                                        onClick = {
                                            clipboard.setText(AnnotatedString(webUrl))
                                            scope.launch { snackbarHostState?.showSnackbar("Web UI URL copied") }
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(16.dp))
                                    }
                                    IconButton(
                                        onClick = {
                                            try {
                                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)).apply {
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                scope.launch { snackbarHostState?.showSnackbar("Cannot open browser: ${e.message}") }
                                            }
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open", modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                        // Moonlight Host IP
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Moonlight Host IP",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SelectionContainer {
                                    Text(
                                        text = ip,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFont),
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(ip))
                                        scope.launch { snackbarHostState?.showSnackbar("Host IP copied") }
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }

                // Control Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!running) {
                        Button(
                            onClick = {
                                scope.launch {
                                    isBusy = true
                                    val res = SunshineManager.startSunshine(containerName)
                                    if (res.isSuccess) snackbarHostState?.showSnackbar("Sunshine started")
                                    else snackbarHostState?.showSnackbar("Failed to start: ${res.message}")
                                    refreshStatus()
                                    isBusy = false
                                }
                            },
                            enabled = !isBusy && isContainerRunning,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Start")
                        }
                    } else {
                        FilledTonalButton(
                            onClick = {
                                scope.launch {
                                    isBusy = true
                                    val res = SunshineManager.stopSunshine(containerName)
                                    if (res.isSuccess) snackbarHostState?.showSnackbar("Sunshine stopped")
                                    refreshStatus()
                                    isBusy = false
                                }
                            },
                            enabled = !isBusy,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Stop")
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    isBusy = true
                                    val res = SunshineManager.restartSunshine(containerName)
                                    if (res.isSuccess) snackbarHostState?.showSnackbar("Sunshine restarted")
                                    refreshStatus()
                                    isBusy = false
                                }
                            },
                            enabled = !isBusy && isContainerRunning,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Restart")
                        }
                    }

                    OutlinedButton(
                        onClick = { showCredsDialog = true },
                        enabled = !isBusy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Web Login")
                    }

                    if (onViewLogs != null) {
                        OutlinedIconButton(
                            onClick = onViewLogs,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Article, contentDescription = "View Logs", modifier = Modifier.size(18.dp))
                        }
                    }
                }

                // Quick Launch Moonlight App on Android
                FilledTonalButton(
                    onClick = {
                        val launched = SunshineManager.launchMoonlightApp(context)
                        if (!launched) {
                            scope.launch { snackbarHostState?.showSnackbar("Unable to launch Moonlight app") }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CastConnected, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Launch Moonlight Client App")
                }

                // Pairing Guide Expandable Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showGuide = !showGuide }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(
                            text = "How to Pair Moonlight with Sunshine",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Icon(
                        if (showGuide) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                if (showGuide) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "1. Open Moonlight app on your device (Android, PC, Steam Deck, TV, etc.).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "2. Tap '+' / Add Host and enter your Host IP ($ip).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "3. Moonlight will show a 4-digit PIN.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "4. Open the Sunshine Web Admin UI ($webUrl), click 'PIN' in the top bar, enter the PIN, and click Send.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "5. Tap the Desktop icon in Moonlight to start high-fps streaming!",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }

    // Credentials Setup Dialog
    if (showCredsDialog) {
        AlertDialog(
            onDismissRequest = { showCredsDialog = false },
            title = { Text("Set Sunshine Web UI Login") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Set the username and password to log into the Sunshine Web Admin interface (https://$connectionIp:47990).",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = adminUser,
                        onValueChange = { adminUser = it },
                        label = { Text("Username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = adminPass,
                        onValueChange = { adminPass = it },
                        label = { Text("Password") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            showCredsDialog = false
                            val res = SunshineManager.setCredentials(containerName, adminUser, adminPass)
                            if (res.isSuccess) {
                                snackbarHostState?.showSnackbar("Credentials updated successfully ✓")
                            } else {
                                snackbarHostState?.showSnackbar("Failed to set credentials: ${res.message}")
                            }
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCredsDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
