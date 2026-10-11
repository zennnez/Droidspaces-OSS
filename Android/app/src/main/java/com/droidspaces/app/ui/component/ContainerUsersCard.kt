package com.droidspaces.app.ui.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.droidspaces.app.ui.component.DsDropdown
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import com.droidspaces.app.ui.util.rememberClearFocus
import androidx.compose.ui.unit.dp
import com.droidspaces.app.util.Constants
import com.droidspaces.app.util.ContainerCommandBuilder
import com.droidspaces.app.util.ContainerInfo
import com.droidspaces.app.util.ContainerUsersManager
import com.droidspaces.app.ui.viewmodel.ContainerOperationsViewModel
import com.droidspaces.app.ui.util.LoadingIndicator
import com.droidspaces.app.ui.util.LoadingSize
import com.droidspaces.app.ui.util.showError
import com.droidspaces.app.ui.util.showSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.droidspaces.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContainerUsersCard(
    container: ContainerInfo,
    refreshTrigger: Int = 0,
    opsViewModel: ContainerOperationsViewModel? = null,
    snackbarHostState: SnackbarHostState? = null,
    onRefreshUsers: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val containerName = container.name
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clearFocus = rememberClearFocus()

    // Use cached value initially for instant display
    var users by remember {
        mutableStateOf<List<String>>(
            ContainerUsersManager.getCachedUsers(containerName) ?: emptyList()
        )
    }
    var isLoading by remember {
        mutableStateOf(ContainerUsersManager.getCachedUsers(containerName) == null)
    }
    var showAddUserDialog by remember { mutableStateOf(false) }

    // Available users list: always include "root", plus any local users
    val availableUsers = remember(users) {
        val userList = users.toMutableList()
        if (!userList.contains("root")) {
            userList.add(0, "root") // Add root at the beginning if not present
        }
        userList
    }

    // Selected user state - default to first non-root user if present, else "root"
    val defaultUser = remember(availableUsers) {
        availableUsers.firstOrNull { it != "root" } ?: "root"
    }
    var selectedUser by remember { mutableStateOf(defaultUser) }

    // State for deleting user
    var userToDelete by remember { mutableStateOf<String?>(null) }
    var isDeletingUser by remember { mutableStateOf(false) }

    // Update selected user when users list changes (prefer non-root if current is root or invalid)
    LaunchedEffect(users) {
        if (!availableUsers.contains(selectedUser) || selectedUser == "root") {
            val nonRoot = availableUsers.firstOrNull { it != "root" }
            selectedUser = nonRoot ?: "root"
        }
    }

    LaunchedEffect(containerName, refreshTrigger) {
        scope.launch {
            val shouldShowLoading = users.isEmpty() && refreshTrigger == 0
            if (shouldShowLoading) {
                isLoading = true
            }
            users = ContainerUsersManager.getUsers(containerName, useCache = refreshTrigger == 0)
            isLoading = false
        }
    }

    fun refreshUsers() {
        scope.launch {
            users = ContainerUsersManager.getUsers(containerName, useCache = false)
            onRefreshUsers()
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Title area
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {
                        clearFocus()
                    }
                ) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = context.getString(R.string.available_users),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Action icons: + Add User and Refresh
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            clearFocus()
                            showAddUserDialog = true
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Add User",
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(Modifier.width(4.dp))

                    DsRefreshButton(
                        contentDescription = context.getString(R.string.refresh_users),
                        onRefresh = {
                            clearFocus()
                            users = ContainerUsersManager.getUsers(containerName, useCache = false)
                            onRefreshUsers()
                        },
                        modifier = Modifier.size(36.dp),
                        iconSize = 20.dp,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            DsDropdown(
                label = context.getString(R.string.select_user),
                selected = selectedUser,
                options = availableUsers,
                displayName = { it },
                onSelect = { selectedUser = it }
            )

            // Action buttons: Delete (for non-root) and Copy Login
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (selectedUser != "root") {
                    FilledTonalButton(
                        onClick = {
                            clearFocus()
                            userToDelete = selectedUser
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Delete",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Button(
                    onClick = {
                        clearFocus()
                        scope.launch {
                            // Check if droidspaces is in PATH, otherwise use full path
                            val droidspacesCmd = withContext(Dispatchers.IO) {
                                Constants.getDroidspacesCommand()
                            }

                            // Quote container name with double quotes
                            val quotedContainerName = "\"${containerName.replace("\"", "\\\"")}\""
                            val loginCommand = if (selectedUser == "root") {
                                "$droidspacesCmd --name=$quotedContainerName enter"
                            } else {
                                "$droidspacesCmd --name=$quotedContainerName enter $selectedUser"
                            }
                            // Wrap entire command in single quotes for su -c
                            val suCommand = "su -c '$loginCommand'"
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText(context.getString(R.string.login_command_copied, selectedUser), suCommand)
                            clipboard.setPrimaryClip(clip)

                            // Show snackbar feedback
                            snackbarHostState?.let { hostState ->
                                scope.launch {
                                    hostState.showSuccess(context.getString(R.string.login_command_copied, selectedUser))
                                }
                            }
                        }
                    },
                    modifier = if (selectedUser != "root") Modifier.weight(1f) else Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        context.getString(R.string.copy_login),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }

    // Warning confirmation dialog for deleting selected user (styled with unified dialog theme)
    userToDelete?.let { targetUser ->
        Dialog(
            onDismissRequest = { if (!isDeletingUser) userToDelete = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                tonalElevation = 0.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Header: Warning Icon + Title + Close button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                text = "Delete User '$targetUser'?",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Surface(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(
                                    enabled = !isDeletingUser,
                                    onClick = { userToDelete = null }
                                ),
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Close",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Text(
                        text = "Are you sure you want to delete user '$targetUser' and their home directory from container '$containerName'?\n\nThis action cannot be undone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { userToDelete = null },
                            enabled = !isDeletingUser
                        ) {
                            Text("Cancel")
                        }

                        Button(
                            onClick = {
                                scope.launch {
                                    isDeletingUser = true
                                    val result = ContainerUsersManager.deleteUser(containerName, targetUser)
                                    isDeletingUser = false
                                    userToDelete = null
                                    if (result.isSuccess) {
                                        snackbarHostState?.let { hostState ->
                                            scope.launch {
                                                hostState.showSuccess("User '$targetUser' deleted successfully")
                                            }
                                        }
                                        if (selectedUser == targetUser) {
                                            selectedUser = "root"
                                        }
                                        refreshUsers()
                                    } else {
                                        snackbarHostState?.let { hostState ->
                                            scope.launch {
                                                hostState.showSnackbar("Failed to delete user: ${result.exceptionOrNull()?.message}")
                                            }
                                        }
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            enabled = !isDeletingUser
                        ) {
                            if (isDeletingUser) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onError
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("Delete", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (showAddUserDialog) {
        AddUserDialog(
            containerName = containerName,
            onDismiss = { showAddUserDialog = false },
            onConfirm = { newUsername, newPassword, grantSudo, shell ->
                showAddUserDialog = false
                if (opsViewModel != null) {
                    scope.launch {
                        opsViewModel.executeAddUser(
                            container = container,
                            username = newUsername,
                            password = newPassword,
                            grantSudo = grantSudo,
                            shell = shell,
                            onRefresh = {
                                refreshUsers()
                                onRefreshUsers()
                            },
                            onError = { err ->
                                snackbarHostState?.let { hostState ->
                                    scope.launch { hostState.showError(err) }
                                }
                            },
                            onSuccess = { msg ->
                                selectedUser = newUsername
                                snackbarHostState?.let { hostState ->
                                    scope.launch { hostState.showSuccess(msg) }
                                }
                            }
                        )
                    }
                }
            }
        )
    }
}

@Composable
fun AddUserDialog(
    containerName: String,
    onDismiss: () -> Unit,
    onConfirm: (username: String, password: String, grantSudo: Boolean, shell: String) -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var grantSudo by remember { mutableStateOf(true) }
    var selectedShell by remember { mutableStateOf("/bin/bash") }
    val availableShells = listOf("/bin/bash", "/bin/sh", "/bin/zsh")
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header: Icon + Title + Close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.PersonAdd,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "Add New User",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Surface(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onDismiss),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Text(
                    text = "Create a new user account inside '$containerName'.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Username field
                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' || c == '-' }
                        errorMessage = null
                    },
                    label = { Text("Username") },
                    placeholder = { Text("e.g. droid, user, dev") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    isError = errorMessage != null
                )

                // Password field
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password (Optional)") },
                    placeholder = { Text("Leave blank for passwordless login") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null
                            )
                        }
                    }
                )

                // Shell selector
                DsDropdown(
                    label = "Login Shell",
                    selected = selectedShell,
                    options = availableShells,
                    displayName = { it },
                    onSelect = { selectedShell = it }
                )

                // Sudo toggle
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { grantSudo = !grantSudo }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Grant Sudo / Root Privileges",
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                if (grantSudo) "Add user to sudo/wheel group & sudoers" else "Standard user without elevated access",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = grantSudo,
                            onCheckedChange = { grantSudo = it }
                        )
                    }
                }

                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = onDismiss) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = {
                            val cleanUser = username.trim()
                            if (cleanUser.isBlank()) {
                                errorMessage = "Username cannot be empty"
                                return@Button
                            }
                            if (cleanUser == "root") {
                                errorMessage = "Root user already exists"
                                return@Button
                            }
                            onConfirm(cleanUser, password, grantSudo, selectedShell)
                        },
                        enabled = username.isNotBlank()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add User", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

