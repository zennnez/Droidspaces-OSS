package com.droidspaces.app.ui.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
import kotlinx.coroutines.launch

/**
 * Runtime status/control for the wireless display (VNC) feature. Only rendered
 * when the container's config has wireless display enabled -- actually
 * installing x11vnc, provisioning a password, and starting the watcher
 * service all happen here, not at container-creation time, since they need a
 * running container to act on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WirelessDisplayCard(
    containerName: String,
    isContainerRunning: Boolean,
    vncPort: Int,
    vncLanOnly: Boolean,
    refreshTrigger: Int = 0,
    snackbarHostState: SnackbarHostState? = null,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clearFocus = rememberClearFocus()

    var status by remember { mutableStateOf<WirelessDisplayManager.VncStatus?>(null) }
    var isLoadingStatus by remember { mutableStateOf(true) }
    var connectionAddress by remember { mutableStateOf<String?>(null) }

    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isBusy by remember { mutableStateOf(false) }

    fun refreshStatus() {
        scope.launch {
            status = WirelessDisplayManager.getStatus(containerName)
            connectionAddress = if (isContainerRunning) {
                WirelessDisplayManager.getConnectionAddress(containerName, vncPort, context)
            } else null
            isLoadingStatus = false
        }
    }

    LaunchedEffect(containerName, refreshTrigger) {
        if (status == null) {
            isLoadingStatus = true
        }
        val s = WirelessDisplayManager.getStatus(containerName)
        val addr = if (isContainerRunning) {
            WirelessDisplayManager.getConnectionAddress(containerName, vncPort, context)
        } else null
        status = s
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
                Icon(Icons.Default.Cast, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    text = context.getString(R.string.wireless_display_card_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (isLoadingStatus) {
                LoadingIndicator(size = LoadingSize.Medium)
            } else {
                val currentStatus = status
                val statusText = when {
                    currentStatus == null || !currentStatus.installed -> context.getString(R.string.vnc_status_not_installed)
                    !currentStatus.hasPassword -> context.getString(R.string.vnc_status_no_password)
                    currentStatus.running -> context.getString(R.string.vnc_status_running, currentStatus.display ?: "?")
                    else -> context.getString(R.string.vnc_status_stopped)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val dotColor = if (currentStatus?.running == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    Icon(
                        if (currentStatus?.running == true) Icons.Default.CheckCircle else Icons.Default.PowerSettingsNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = dotColor
                    )
                    Text(text = statusText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                if (currentStatus?.installed != true) {
                    Button(
                        onClick = {
                            isBusy = true
                            scope.launch {
                                val res = WirelessDisplayManager.installX11vnc(containerName)
                                if (res.isSuccess) {
                                    snackbarHostState?.showSuccess("x11vnc installed successfully")
                                } else {
                                    snackbarHostState?.showError(res.message.ifBlank { "Failed to install x11vnc" })
                                }
                                refreshStatus()
                                isBusy = false
                            }
                        },
                        enabled = !isBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isBusy) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Install x11vnc")
                    }
                }

                // Connection address, once running-eligible
                if (currentStatus?.hasPassword == true) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = context.getString(R.string.vnc_connection_address),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = connectionAddress ?: context.getString(R.string.vnc_no_address_yet),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (connectionAddress != null) {
                            IconButton(onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("VNC address", connectionAddress))
                                scope.launch { snackbarHostState?.showSuccess(context.getString(R.string.vnc_address_copied)) }
                            }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null)
                            }
                        }
                    }
                    Text(
                        text = "Connect using a VNC viewer app (e.g. AVNC, RealVNC, bVNC) with the address above. Requires an active Termux:X11 desktop session inside the container.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }

                // Password entry -- shown until a password is set, then collapses to change-password affordance
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = {
                            Text(
                                if (currentStatus?.hasPassword == true) context.getString(R.string.vnc_change_password)
                                else context.getString(R.string.vnc_password_label)
                            )
                        },
                        placeholder = { Text(context.getString(R.string.vnc_password_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null)
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors = DsTextFieldDefaults.colors()
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                clearFocus()
                                if (password.length < 6) {
                                    scope.launch { snackbarHostState?.showError(context.getString(R.string.vnc_password_too_short)) }
                                    return@Button
                                }
                                isBusy = true
                                scope.launch {
                                    val pwResult = WirelessDisplayManager.setPassword(containerName, password)
                                    password = ""
                                    if (!pwResult.isSuccess) {
                                        snackbarHostState?.showError(pwResult.message.ifBlank { "Failed to set VNC password" })
                                        isBusy = false
                                        return@launch
                                    }
                                    val enableResult = WirelessDisplayManager.enable(containerName, vncPort, vncLanOnly)
                                    if (enableResult.isSuccess) {
                                        snackbarHostState?.showSuccess(context.getString(R.string.vnc_password_set_success))
                                    } else {
                                        snackbarHostState?.showError(enableResult.message.ifBlank { "Failed to start wireless display" })
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
                                Text(context.getString(R.string.vnc_set_password_and_enable), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                if (currentStatus?.hasPassword == true) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                clearFocus()
                                isBusy = true
                                scope.launch {
                                    val result = WirelessDisplayManager.restart(containerName)
                                    if (result.isSuccess) {
                                        snackbarHostState?.showSuccess(context.getString(R.string.vnc_restarted_success))
                                    } else {
                                        snackbarHostState?.showError(result.message.ifBlank { "Failed to restart" })
                                    }
                                    refreshStatus()
                                    isBusy = false
                                }
                            },
                            enabled = !isBusy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(context.getString(R.string.vnc_restart_service))
                        }

                        OutlinedButton(
                            onClick = {
                                clearFocus()
                                isBusy = true
                                scope.launch {
                                    val result = WirelessDisplayManager.disable(containerName)
                                    if (result.isSuccess) {
                                        snackbarHostState?.showSuccess(context.getString(R.string.vnc_disabled_success))
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
                            Text(context.getString(R.string.vnc_disable))
                        }
                    }
                }
            }
        }
    }
}
