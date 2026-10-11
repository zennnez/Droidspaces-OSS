package com.droidspaces.app.ui.screen

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.droidspaces.app.R
import com.droidspaces.app.ui.component.EnvironmentVariablesDialog
import com.droidspaces.app.ui.component.LogViewerDialog
import com.droidspaces.app.ui.component.SettingsRowCard
import com.droidspaces.app.ui.component.TerminalDialog
import com.droidspaces.app.ui.viewmodel.ContainerOperationsViewModel
import com.droidspaces.app.util.AnlandUtils
import com.droidspaces.app.util.Constants
import com.droidspaces.app.util.ContainerCommandBuilder
import com.droidspaces.app.util.ContainerEnvironmentManager
import com.droidspaces.app.util.ContainerInfo
import com.droidspaces.app.util.ContainerManager
import com.droidspaces.app.util.PreferencesManager
import com.droidspaces.app.util.ValidationUtils
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WaylandDesktopPreset(
    val id: String,
    val name: String,
    val tagline: String,
    val description: String,
    val startCommand: String,
    val installCommand: String,
    val binaryCheck: String,
    val serviceUnit: String? = null
)

val WAYLAND_DESKTOPS = listOf(
    WaylandDesktopPreset(
        id = "kde_wayland",
        name = "KDE Plasma (Wayland)",
        tagline = "Modern, full-featured desktop",
        description = "Hardware-accelerated KDE Plasma session built with Anland-optimized KWin & Xwayland",
        startCommand = "touch ~/.zshrc ~/.bashrc 2>/dev/null; if [ -f /usr/local/sbin/fix-kde-runtime.sh ]; then sh /usr/local/sbin/fix-kde-runtime.sh 2>/dev/null || true; elif [ -f /run/droidspaces/assets/fix-kde-runtime.sh ]; then sh /run/droidspaces/assets/fix-kde-runtime.sh 2>/dev/null || true; fi; _U=\$(id -u); _UN=\$(id -un); if [ -d /run/user/\$_U ]; then export XDG_RUNTIME_DIR=/run/user/\$_U; else export XDG_RUNTIME_DIR=/tmp/runtime-\$_UN; mkdir -p -m 0700 \"\$XDG_RUNTIME_DIR\" 2>/dev/null || true; fi; ln -sfn \"\$XDG_RUNTIME_DIR\" /tmp/runtime-\$_UN 2>/dev/null || true; ln -sfn \"\$XDG_RUNTIME_DIR\" /run/user/\$_U 2>/dev/null || true; if [ -z \"\$DBUS_SESSION_BUS_ADDRESS\" ] && command -v dbus-run-session >/dev/null 2>&1; then dbus-run-session startplasma-wayland; else startplasma-wayland; fi",
        installCommand = "curl -fLO https://raw.githubusercontent.com/Goldzxcbug/Droidspaces-rootfs-KDE-builder/main/scripts/install-anland-kde.sh && chmod +x install-anland-kde.sh && ./install-anland-kde.sh",
        binaryCheck = "startplasma-wayland",
        serviceUnit = "plasma-wayland.service"
    ),
    WaylandDesktopPreset(
        id = "kde_mobile",
        name = "KDE Plasma Mobile",
        tagline = "Touch-first phone UI",
        description = "Touch-optimized KDE Plasma interface designed for smartphone screens, gesture navigation, and mobile apps",
        startCommand = "touch ~/.zshrc ~/.bashrc 2>/dev/null; if [ -f /usr/local/sbin/fix-kde-runtime.sh ]; then sh /usr/local/sbin/fix-kde-runtime.sh 2>/dev/null || true; elif [ -f /run/droidspaces/assets/fix-kde-runtime.sh ]; then sh /run/droidspaces/assets/fix-kde-runtime.sh 2>/dev/null || true; fi; _U=\$(id -u); _UN=\$(id -un); if [ -d /run/user/\$_U ]; then export XDG_RUNTIME_DIR=/run/user/\$_U; else export XDG_RUNTIME_DIR=/tmp/runtime-\$_UN; mkdir -p -m 0700 \"\$XDG_RUNTIME_DIR\" 2>/dev/null || true; fi; ln -sfn \"\$XDG_RUNTIME_DIR\" /tmp/runtime-\$_UN 2>/dev/null || true; ln -sfn \"\$XDG_RUNTIME_DIR\" /run/user/\$_U 2>/dev/null || true; if [ -z \"\$DBUS_SESSION_BUS_ADDRESS\" ] && command -v dbus-run-session >/dev/null 2>&1; then dbus-run-session startplasmamobile; else startplasmamobile; fi",
        installCommand = "if command -v apt-get >/dev/null 2>&1; then apt-get update && apt-get install -y plasma-mobile plasma-mobile-settings; elif command -v pacman >/dev/null 2>&1; then pacman -Sy --noconfirm plasma-mobile; elif command -v dnf >/dev/null 2>&1; then dnf install -y plasma-mobile; fi",
        binaryCheck = "startplasmamobile",
        serviceUnit = "plasma-mobile.service"
    ),
    WaylandDesktopPreset(
        id = "kde_x11",
        name = "KDE Plasma (X11)",
        tagline = "Classic X11 session (Termux:X11)",
        description = "KDE Plasma session configured for X11 / Termux:X11 display (DISPLAY=:5 or :0)",
        startCommand = "touch ~/.zshrc ~/.bashrc 2>/dev/null; export DISPLAY=\"\${DISPLAY:-:5}\"; export XDG_RUNTIME_DIR=\"\${XDG_RUNTIME_DIR:-/tmp/runtime-\$(id -un)}\"; mkdir -p -m 0700 \"\$XDG_RUNTIME_DIR\"; if [ -z \"\$DBUS_SESSION_BUS_ADDRESS\" ] && command -v dbus-run-session >/dev/null 2>&1; then dbus-run-session startplasma-x11; else startplasma-x11; fi",
        installCommand = "if command -v apt-get >/dev/null 2>&1; then apt-get update && apt-get install -y plasma-desktop; elif command -v pacman >/dev/null 2>&1; then pacman -Sy --noconfirm plasma-desktop; fi",
        binaryCheck = "startplasma-x11",
        serviceUnit = "plasma-x11.service"
    ),
    WaylandDesktopPreset(
        id = "xfce",
        name = "XFCE (Wayland)",
        tagline = "Lightweight classic desktop",
        description = "XFCE desktop session launched with Wayland compositor (Labwc / Wayfire)",
        startCommand = "touch ~/.zshrc ~/.bashrc 2>/dev/null; export XDG_RUNTIME_DIR=\"\${XDG_RUNTIME_DIR:-/tmp/runtime-\$(id -un)}\"; mkdir -p -m 0700 \"\$XDG_RUNTIME_DIR\"; if command -v labwc >/dev/null 2>&1; then labwc -s xfce4-session; elif command -v wayfire >/dev/null 2>&1; then wayfire -c /etc/wayfire/wayfire.ini; else xfce4-session; fi",
        installCommand = "if command -v apt-get >/dev/null 2>&1; then apt-get update && apt-get install -y xfce4 xfce4-terminal labwc; elif command -v pacman >/dev/null 2>&1; then pacman -Sy --noconfirm xfce4 labwc; elif command -v apk >/dev/null 2>&1; then apk add xfce4 labwc; fi",
        binaryCheck = "xfce4-session"
    ),
    WaylandDesktopPreset(
        id = "sway",
        name = "Sway (i3 Tiling)",
        tagline = "Ultra-fast tiling compositor",
        description = "i3-compatible keyboard-driven tiling Wayland compositor with minimal footprint",
        startCommand = "touch ~/.zshrc ~/.bashrc 2>/dev/null; export XDG_RUNTIME_DIR=\"\${XDG_RUNTIME_DIR:-/tmp/runtime-\$(id -un)}\"; mkdir -p -m 0700 \"\$XDG_RUNTIME_DIR\"; sway --unsupported-gpu",
        installCommand = "if command -v apt-get >/dev/null 2>&1; then apt-get update && apt-get install -y sway swaybg swayidle foot; elif command -v pacman >/dev/null 2>&1; then pacman -Sy --noconfirm sway swaybg foot; elif command -v apk >/dev/null 2>&1; then apk add sway foot; fi",
        binaryCheck = "sway"
    ),
    WaylandDesktopPreset(
        id = "wayfire",
        name = "Wayfire (3D Effects)",
        tagline = "3D animations compositor",
        description = "Compiz-inspired 3D Wayland compositor with smooth animations and plugins",
        startCommand = "touch ~/.zshrc ~/.bashrc 2>/dev/null; export XDG_RUNTIME_DIR=\"\${XDG_RUNTIME_DIR:-/tmp/runtime-\$(id -un)}\"; mkdir -p -m 0700 \"\$XDG_RUNTIME_DIR\"; wayfire",
        installCommand = "if command -v apt-get >/dev/null 2>&1; then apt-get update && apt-get install -y wayfire wayfire-plugins-extra foot; elif command -v pacman >/dev/null 2>&1; then pacman -Sy --noconfirm wayfire foot; elif command -v apk >/dev/null 2>&1; then apk add wayfire foot; fi",
        binaryCheck = "wayfire"
    ),
    WaylandDesktopPreset(
        id = "labwc",
        name = "Labwc (Stacking WM)",
        tagline = "Openbox-like Wayland window manager",
        description = "Lightweight, stacking Wayland window manager inspired by Openbox",
        startCommand = "touch ~/.zshrc ~/.bashrc 2>/dev/null; export XDG_RUNTIME_DIR=\"\${XDG_RUNTIME_DIR:-/tmp/runtime-\$(id -un)}\"; mkdir -p -m 0700 \"\$XDG_RUNTIME_DIR\"; labwc",
        installCommand = "if command -v apt-get >/dev/null 2>&1; then apt-get update && apt-get install -y labwc foot; elif command -v pacman >/dev/null 2>&1; then pacman -Sy --noconfirm labwc foot; elif command -v apk >/dev/null 2>&1; then apk add labwc foot; fi",
        binaryCheck = "labwc"
    ),
    WaylandDesktopPreset(
        id = "weston",
        name = "Weston",
        tagline = "Reference Wayland compositor",
        description = "Official reference Wayland compositor with basic desktop shell",
        startCommand = "touch ~/.zshrc ~/.bashrc 2>/dev/null; export XDG_RUNTIME_DIR=\"\${XDG_RUNTIME_DIR:-/tmp/runtime-\$(id -un)}\"; mkdir -p -m 0700 \"\$XDG_RUNTIME_DIR\"; weston",
        installCommand = "if command -v apt-get >/dev/null 2>&1; then apt-get update && apt-get install -y weston; elif command -v pacman >/dev/null 2>&1; then pacman -Sy --noconfirm weston; elif command -v apk >/dev/null 2>&1; then apk add weston; fi",
        binaryCheck = "weston"
    ),
    WaylandDesktopPreset(
        id = "gnome",
        name = "GNOME",
        tagline = "Modern GNOME Shell session",
        description = "GNOME desktop environment Wayland session",
        startCommand = "touch ~/.zshrc ~/.bashrc 2>/dev/null; export XDG_RUNTIME_DIR=\"\${XDG_RUNTIME_DIR:-/tmp/runtime-\$(id -un)}\"; mkdir -p -m 0700 \"\$XDG_RUNTIME_DIR\"; gnome-session",
        installCommand = "if command -v apt-get >/dev/null 2>&1; then apt-get update && apt-get install -y gnome-session gnome-terminal; fi",
        binaryCheck = "gnome-session"
    ),
    WaylandDesktopPreset(
        id = "niri",
        name = "Niri (Scrollable Tiling)",
        tagline = "Modern scrollable tiling compositor",
        description = "Smooth scrollable tiling Wayland compositor with Noctalia shell and Anland backend integration",
        startCommand = "touch ~/.zshrc ~/.bashrc 2>/dev/null; export XDG_RUNTIME_DIR=\"\${XDG_RUNTIME_DIR:-/tmp/runtime-\$(id -un)}\"; mkdir -p -m 0700 \"\$XDG_RUNTIME_DIR\"; export QT_QPA_PLATFORM=wayland; export GTK_THEME=Adwaita:dark; if [ -S /run/display.sock ]; then if command -v niri >/dev/null 2>&1; then niri --anland; else /usr/local/bin/niri --anland; fi; else niri; fi",
        installCommand = "if [ -f /usr/local/sbin/install-anland-niri.sh ]; then /usr/local/sbin/install-anland-niri.sh; elif curl -fsSL https://raw.githubusercontent.com/Goldzxcbug/Droidspaces-rootfs-KDE-builder/main/scripts/install-anland-niri.sh -o /tmp/install-niri.sh 2>/dev/null; then bash /tmp/install-niri.sh; elif command -v pacman >/dev/null 2>&1; then pacman -Sy --noconfirm --needed niri noctalia-shell foot xdg-desktop-portal xdg-desktop-portal-gtk qt6-wayland wayland-utils; elif command -v dnf >/dev/null 2>&1; then dnf install -y niri noctalia foot xdg-desktop-portal xdg-desktop-portal-gtk qt6-qtwayland wayland-utils; elif command -v apt-get >/dev/null 2>&1; then apt-get update && (apt-get install -y niri foot xdg-desktop-portal xdg-desktop-portal-gtk qt6-wayland wayland-utils 2>/dev/null || (apt-get install -y foot xdg-desktop-portal xdg-desktop-portal-gtk qt6-wayland wayland-utils && curl -fsSL https://github.com/niri-wm/niri/releases/latest/download/niri-linux-$(uname -m).tar.gz 2>/dev/null | tar -xz -C /usr/local/bin niri 2>/dev/null || true)); elif command -v apk >/dev/null 2>&1; then apk add --no-cache niri foot xdg-desktop-portal xdg-desktop-portal-wlr xdg-desktop-portal-gtk qt6-qtwayland wayland-utils; fi",
        binaryCheck = "niri",
        serviceUnit = "niri.service"
    ),
    WaylandDesktopPreset(
        id = "noctalia",
        name = "Noctalia Shell",
        tagline = "Modern Wayland desktop shell",
        description = "Lightweight desktop shell providing taskbar, app launcher, system trays, and notifications for Niri/Wayland",
        startCommand = "touch ~/.zshrc ~/.bashrc 2>/dev/null; export XDG_RUNTIME_DIR=\"\${XDG_RUNTIME_DIR:-/tmp/runtime-\$(id -un)}\"; mkdir -p -m 0700 \"\$XDG_RUNTIME_DIR\"; export QT_QPA_PLATFORM=wayland; export GTK_THEME=Adwaita:dark; noctalia",
        installCommand = "if command -v pacman >/dev/null 2>&1; then pacman -Sy --noconfirm --needed noctalia-shell; elif command -v dnf >/dev/null 2>&1; then dnf install -y noctalia; fi",
        binaryCheck = "noctalia",
        serviceUnit = "noctalia.service"
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnlandScreen(
    containerName: String,
    onNavigateBack: () -> Unit,
    onNavigateToGuide: () -> Unit,
    onStartDesktopTerminal: (String) -> Unit,
    opsViewModel: ContainerOperationsViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val prefsManager = remember { PreferencesManager.getInstance(context) }

    var container by remember { mutableStateOf<ContainerInfo?>(null) }
    var socketPath by remember { mutableStateOf<String?>(null) }
    var isEnvConfigured by remember { mutableStateOf(false) }
    var hasSnapdragonFix by remember { mutableStateOf(false) }
    var isConsumerInstalled by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }

    var defaultDesktopId by remember { mutableStateOf(prefsManager.getDefaultWaylandDesktop(containerName)) }
    var selectedDesktop by remember {
        val initial = WAYLAND_DESKTOPS.find { it.id == defaultDesktopId } ?: WAYLAND_DESKTOPS[0]
        mutableStateOf(initial)
    }
    var installedStatusMap by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var customCommand by remember { mutableStateOf("") }
    var isCustomSelected by remember { mutableStateOf(false) }
    var anlandEnvContent by remember { mutableStateOf("") }
    var showEnvDialog by remember { mutableStateOf(false) }
    var showAnlandLogs by remember { mutableStateOf(false) }

    var selectedKeyboardId by remember { mutableStateOf(prefsManager.getAnlandSelectedKeyboard(containerName)) }
    var isKdeKeyboardInstalled by remember { mutableStateOf(false) }
    var isVboardInstalled by remember { mutableStateOf(false) }

    suspend fun checkStatus() {
        container = ContainerManager.getContainerInfo(containerName)
        socketPath = ContainerManager.getAnlandSocket(containerName)

        val envOut = ContainerEnvironmentManager.loadAnlandEnv(containerName)
        anlandEnvContent = envOut
        isEnvConfigured = envOut.contains("WAYLAND_DISPLAY=") && envOut.contains("ANLAND_SOCKET=")
        hasSnapdragonFix = envOut.contains("FD_DEV_FEATURES=enable_tp_ubwc_flag_hint=1")

        isConsumerInstalled = isPackageInstalled(context, "com.anland.consumer")

        // Check installed binaries
        val checks = WAYLAND_DESKTOPS.joinToString("; ") { "command -v ${it.binaryCheck} >/dev/null 2>&1 && echo '${it.id}:1' || echo '${it.id}:0'" }
        val kbChecks = "(command -v maliit-keyboard >/dev/null 2>&1 || [ -f /usr/local/bin/kde-virtual-keyboard ]) && echo 'kb_kde:1' || echo 'kb_kde:0'; " +
            "(command -v wvkbd >/dev/null 2>&1 || command -v squeekboard >/dev/null 2>&1 || [ -f /usr/local/bin/vboard ]) && echo 'kb_vboard:1' || echo 'kb_vboard:0'"
        val checkCmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(containerName)} run '$checks; $kbChecks'"
        val checkOut = withContext(Dispatchers.IO) { Shell.cmd(checkCmd).exec().out }
        val map = mutableMapOf<String, Boolean>()
        checkOut.forEach { line ->
            val parts = line.split(":")
            if (parts.size == 2) {
                val k = parts[0].trim()
                val v = parts[1].trim() == "1"
                when (k) {
                    "kb_kde" -> isKdeKeyboardInstalled = v
                    "kb_vboard" -> isVboardInstalled = v
                    else -> map[k] = v
                }
            }
        }
        installedStatusMap = map
    }

    LaunchedEffect(containerName) {
        checkStatus()
        loading = false
    }

    if (showEnvDialog) {
        EnvironmentVariablesDialog(
            initialContent = anlandEnvContent,
            onConfirm = { newContent ->
                scope.launch {
                    busy = true
                    ContainerEnvironmentManager.saveAnlandEnv(context, containerName, newContent)
                    checkStatus()
                    snackbarHostState.showSnackbar("Wayland environment variables (.anland.env) updated successfully ✓")
                    busy = false
                }
                showEnvDialog = false
            },
            onDismiss = { showEnvDialog = false }
        )
    }
    
    if (showAnlandLogs) {
        LogViewerDialog(
            title = "Anland Wayland Logs",
            subtitle = containerName,
            logFileName = "${containerName}_anland.log",
            fetchLogs = {
                val cmd = "${Constants.DROIDSPACES_BINARY_PATH} --name=${ContainerCommandBuilder.quote(containerName)} run 'cat /run/anland.log /tmp/anland.log /run/droidspaces-stream/encoder.log /tmp/wayland.log 2>/dev/null || journalctl -u anland -n 100 --no-pager 2>/dev/null || echo \"No Anland logs found\"'"
                try {
                    val res = withContext(Dispatchers.IO) { Shell.cmd(cmd).exec() }
                    res.out.joinToString("\n").ifBlank { "No log entries found." }
                } catch (e: Exception) {
                    e.message ?: "Failed to fetch logs"
                }
            },
            onDismiss = { showAnlandLogs = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Anland Wayland",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = containerName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showAnlandLogs = true }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Article,
                            contentDescription = "View Logs"
                        )
                    }
                    IconButton(onClick = {
                        scope.launch {
                            loading = true
                            checkStatus()
                            loading = false
                            snackbarHostState.showSnackbar("Status refreshed")
                        }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onNavigateToGuide) {
                        Icon(Icons.Default.Info, contentDescription = "Anland Guide")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Status Header Card
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (socketPath != null) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DesktopWindows,
                                    contentDescription = null,
                                    tint = if (socketPath != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = "Wayland Compositor Bridge",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.padding(vertical = 2.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (socketPath != null) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.outline
                                            )
                                    )
                                    Text(
                                        text = if (socketPath != null) "Active" else "Offline",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (socketPath != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                    )
                                }
                                Text(
                                    text = if (socketPath != null) "Anland daemon socket is active at $socketPath"
                                    else "Daemon socket not detected. Start container with Anland enabled.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                        // Status Info Chips
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatusRow(
                                label = "Socket Path",
                                value = socketPath ?: "/run/display.sock (inactive)",
                                isMonospace = true,
                                isGood = socketPath != null
                            )
                            StatusRow(
                                label = "Environment Config",
                                value = if (isEnvConfigured) "Configured in /etc/environment" else "Not Configured",
                                isGood = isEnvConfigured
                            )
                            StatusRow(
                                label = "Anland Consumer App",
                                value = if (isConsumerInstalled) "Installed on Android ✓" else "Not Installed",
                                isGood = isConsumerInstalled
                            )
                        }
                    }
                }
            }

            // 2. Desktop Environment Selection & Launcher Card
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "Wayland Desktop Environment",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        // Desktop Selection Horizontal Chips
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(WAYLAND_DESKTOPS) { desktop ->
                                val isSelected = !isCustomSelected && selectedDesktop.id == desktop.id
                                val isInstalled = installedStatusMap[desktop.id] == true
                                val isDefault = desktop.id == defaultDesktopId

                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        isCustomSelected = false
                                        selectedDesktop = desktop
                                    },
                                    label = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(desktop.name, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                            if (isDefault) {
                                                Surface(
                                                    shape = RoundedCornerShape(50),
                                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.25f) else MaterialTheme.colorScheme.primaryContainer
                                                ) {
                                                    Text(
                                                        text = "DEFAULT",
                                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                                        fontWeight = FontWeight.Black,
                                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                    )
                                                }
                                            } else if (isInstalled) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(6.dp)
                                                        .clip(CircleShape)
                                                        .background(if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary)
                                                )
                                            }
                                        }
                                    },
                                    shape = RoundedCornerShape(50),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                )
                            }

                            item {
                                FilterChip(
                                    selected = isCustomSelected,
                                    onClick = { isCustomSelected = true },
                                    label = { Text("Custom Command", fontWeight = if (isCustomSelected) FontWeight.Bold else FontWeight.Normal) },
                                    shape = RoundedCornerShape(50),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                )
                            }
                        }

                        // Selected Desktop Details Box
                        if (!isCustomSelected) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = selectedDesktop.name,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            if (selectedDesktop.id == defaultDesktopId) {
                                                Surface(
                                                    shape = RoundedCornerShape(50),
                                                    color = MaterialTheme.colorScheme.primaryContainer
                                                ) {
                                                    Text(
                                                        text = "DEFAULT",
                                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                        fontWeight = FontWeight.Black,
                                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                        val isInstalled = installedStatusMap[selectedDesktop.id] == true
                                        Text(
                                            text = if (isInstalled) "Installed ✓" else "Not installed",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isInstalled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                        )
                                    }
                                    Text(
                                        text = selectedDesktop.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            OutlinedTextField(
                                value = customCommand,
                                onValueChange = { customCommand = it },
                                label = { Text("Custom Startup Command") },
                                placeholder = { Text("e.g. weston or sway or labwc") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true
                            )
                        }

                        // ── Buttons: 2 in a row ──

                        // Row 1: Start Button + Install Button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    val cmd = if (isCustomSelected) {
                                        "touch ~/.zshrc ~/.bashrc 2>/dev/null; export XDG_RUNTIME_DIR=\"\${XDG_RUNTIME_DIR:-/tmp/runtime-\$(id -un)}\"; mkdir -p -m 0700 \"\$XDG_RUNTIME_DIR\"; $customCommand"
                                    } else {
                                        selectedDesktop.startCommand
                                    }
                                    onStartDesktopTerminal(cmd)
                                },
                                enabled = !busy && (!isCustomSelected || customCommand.isNotBlank()),
                                shape = RoundedCornerShape(50),
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = if (isCustomSelected) "Start Custom" else "Start ${selectedDesktop.name}",
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    if (selectedDesktop.id == "kde_wayland" || selectedDesktop.installCommand.contains("install-anland-kde.sh")) {
                                        onStartDesktopTerminal(selectedDesktop.installCommand)
                                    } else {
                                        scope.launch {
                                            container?.let {
                                                opsViewModel.executeInstallWaylandDesktop(
                                                    container = it,
                                                    desktopName = selectedDesktop.name,
                                                    installCommand = selectedDesktop.installCommand,
                                                    onRefresh = { scope.launch { checkStatus() } },
                                                    onError = { err: String -> scope.launch { snackbarHostState.showSnackbar(err) } },
                                                    onSuccess = { msg: String -> scope.launch { snackbarHostState.showSnackbar(msg) } }
                                                )
                                            }
                                        }
                                    }
                                },
                                enabled = !busy && !isCustomSelected,
                                shape = RoundedCornerShape(50),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Install Packages", fontWeight = FontWeight.SemiBold, maxLines = 1)
                            }
                        }

                        // Row 2: Auto-Configure Button + Install USB Manager Button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        container?.let {
                                            opsViewModel.executeAutoConfigureAnlandEnvironment(
                                                container = it,
                                                hasSnapdragonFix = hasSnapdragonFix,
                                                selectedDesktopServiceName = selectedDesktop.serviceUnit,
                                                selectedDesktopStartCommand = selectedDesktop.startCommand,
                                                onRefresh = { scope.launch { checkStatus() } },
                                                onError = { err: String -> scope.launch { snackbarHostState.showSnackbar(err) } },
                                                onSuccess = { msg: String -> scope.launch { snackbarHostState.showSnackbar(msg) } }
                                            )
                                        }
                                    }
                                },
                                enabled = !busy,
                                shape = RoundedCornerShape(50),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Auto-Configure", fontWeight = FontWeight.SemiBold, maxLines = 1)
                            }

                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        container?.let {
                                            opsViewModel.executeInstallUsbManager(
                                                container = it,
                                                onRefresh = { scope.launch { checkStatus() } },
                                                onError = { err: String -> scope.launch { snackbarHostState.showSnackbar(err) } },
                                                onSuccess = { msg: String -> scope.launch { snackbarHostState.showSnackbar(msg) } }
                                            )
                                        }
                                    }
                                },
                                enabled = !busy,
                                shape = RoundedCornerShape(50),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Devices, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Install USB Manager", fontWeight = FontWeight.SemiBold, maxLines = 1)
                            }
                        }

                        // Make Default Desktop Button (if not already default)
                        if (!isCustomSelected && selectedDesktop.id != defaultDesktopId) {
                            OutlinedButton(
                                onClick = {
                                    prefsManager.setDefaultWaylandDesktop(containerName, selectedDesktop.id)
                                    defaultDesktopId = selectedDesktop.id
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Set ${selectedDesktop.name} as default desktop ✓")
                                    }
                                },
                                shape = RoundedCornerShape(50),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Make Default Desktop", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            // 3. Launch Anland Multi-Window (Single Full Row Button without Actions text)
            item {
                Button(
                    onClick = {
                        val sock = socketPath ?: "/data/local/tmp/display_daemon.sock"
                        AnlandUtils.launchWindow(context, containerName, sock)
                    },
                    enabled = !busy,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Launch Anland Multi-Window", fontWeight = FontWeight.SemiBold)
                }
            }

            // 4. Virtual Keyboards Section
            item {
                AnlandKeyboardsSection(
                    container = container,
                    selectedKeyboardId = selectedKeyboardId,
                    onSelectKeyboard = { newKb ->
                        selectedKeyboardId = newKb
                        prefsManager.setAnlandSelectedKeyboard(containerName, newKb)
                        scope.launch {
                            container?.let {
                                opsViewModel.executeConfigureAnlandKeyboard(
                                    container = it,
                                    keyboardType = newKb,
                                    onRefresh = { scope.launch { checkStatus() } },
                                    onError = { err: String -> scope.launch { snackbarHostState.showSnackbar(err) } },
                                    onSuccess = { msg: String -> scope.launch { snackbarHostState.showSnackbar(msg) } }
                                )
                            }
                        }
                    },
                    isKdeKeyboardInstalled = isKdeKeyboardInstalled,
                    isVboardInstalled = isVboardInstalled,
                    busy = busy || opsViewModel.runningOperationContainer == containerName,
                    onInstallKdeKeyboard = {
                        scope.launch {
                            container?.let {
                                opsViewModel.executeInstallKdeKeyboard(
                                    container = it,
                                    onRefresh = { scope.launch { checkStatus() } },
                                    onError = { err: String -> scope.launch { snackbarHostState.showSnackbar(err) } },
                                    onSuccess = { msg: String -> scope.launch { snackbarHostState.showSnackbar(msg) } }
                                )
                            }
                        }
                    },
                    onInstallVboard = {
                        scope.launch {
                            container?.let {
                                opsViewModel.executeInstallVboard(
                                    container = it,
                                    onRefresh = { scope.launch { checkStatus() } },
                                    onError = { err: String -> scope.launch { snackbarHostState.showSnackbar(err) } },
                                    onSuccess = { msg: String -> scope.launch { snackbarHostState.showSnackbar(msg) } }
                                )
                            }
                        }
                    }
                )
            }

            // 5. Environment Variables & Hardware Settings Card
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "Wayland Environment & Settings",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        val envCount = ValidationUtils.countEnvVars(anlandEnvContent)
                        val envSubtitle = if (envCount > 0) {
                            context.getString(R.string.environment_variables_configured, envCount)
                        } else {
                            context.getString(R.string.not_configured)
                        }

                        SettingsRowCard(
                            title = "Wayland Environment Variables",
                            subtitle = envSubtitle,
                            description = "Configured in /etc/environment inside the container",
                            icon = Icons.Default.Code,
                            onClick = { showEnvDialog = true }
                        )

                        // Snapdragon 8 Gen 2 / UBWC Flag Toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Snapdragon 8 Gen 2 Workaround",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Inject FD_DEV_FEATURES flag to fix black screen/UBWC crashes",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = hasSnapdragonFix,
                                onCheckedChange = { checked ->
                                    hasSnapdragonFix = checked
                                    scope.launch {
                                        busy = true
                                        var env = ContainerEnvironmentManager.loadAnlandEnv(containerName)
                                        val flag = "FD_DEV_FEATURES=enable_tp_ubwc_flag_hint=1"
                                        env = if (checked) {
                                            if (!env.contains("FD_DEV_FEATURES=")) {
                                                if (env.isBlank()) flag else "$env\n$flag"
                                            } else {
                                                env
                                            }
                                        } else {
                                            env.lines().filterNot { it.trim().startsWith("FD_DEV_FEATURES=") }.joinToString("\n")
                                        }
                                        ContainerEnvironmentManager.saveAnlandEnv(context, containerName, env)
                                        busy = false
                                        checkStatus()
                                        snackbarHostState.showSnackbar(
                                            if (checked) "Snapdragon UBWC workaround enabled"
                                            else "Snapdragon workaround disabled"
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        // Live terminal console for container operations
        opsViewModel.showLogViewerFor?.let { targetContainer ->
            if (targetContainer == containerName) {
                val memoryLogs = opsViewModel.logsFor(targetContainer)
                val prefsManager = remember { PreferencesManager.getInstance(context) }
                val cachedLogs = if (memoryLogs.isEmpty()) prefsManager.loadContainerLogs(targetContainer) else emptyList()
                val logs = memoryLogs.ifEmpty { cachedLogs }
                val isBlocking = opsViewModel.runningOperationContainer == targetContainer

                TerminalDialog(
                    title = context.getString(R.string.logs_title, targetContainer),
                    logs = logs,
                    onDismiss = {
                        opsViewModel.showLogViewerFor = null
                        scope.launch { checkStatus() }
                    },
                    onClear = {
                        opsViewModel.clearLogsBuffer(targetContainer)
                    },
                    isBlocking = isBlocking
                )
            }
        }
    }
}

@Composable
private fun StatusRow(
    label: String,
    value: String,
    isGood: Boolean,
    isMonospace: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = if (isMonospace) MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (isGood) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun isPackageInstalled(context: Context, packageName: String): Boolean {
    return try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: Exception) {
        false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnlandKeyboardsSection(
    container: ContainerInfo?,
    selectedKeyboardId: String,
    onSelectKeyboard: (String) -> Unit,
    isKdeKeyboardInstalled: Boolean,
    isVboardInstalled: Boolean,
    busy: Boolean,
    onInstallKdeKeyboard: () -> Unit,
    onInstallVboard: () -> Unit
) {
    var expandedDropdown by remember { mutableStateOf(false) }
    val keyboardOptions = listOf(
        "kde" to "KDE Plasma Keyboard",
        "vboard" to "vboard"
    )

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Keyboard, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        }
                    }
                    Column {
                        Text(
                            text = "Keyboards",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Choose active on-screen keyboard for Anland sessions",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Keyboard Selection Dropdown
            ExposedDropdownMenuBox(
                expanded = expandedDropdown,
                onExpandedChange = { if (!busy) expandedDropdown = !expandedDropdown },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = keyboardOptions.find { it.first == selectedKeyboardId }?.second ?: "KDE Plasma Keyboard",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Active Anland Keyboard") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedDropdown) },
                    leadingIcon = { Icon(Icons.Default.TouchApp, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )

                ExposedDropdownMenu(
                    expanded = expandedDropdown,
                    onDismissRequest = { expandedDropdown = false }
                ) {
                    keyboardOptions.forEach { (id, label) ->
                        DropdownMenuItem(
                            text = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(label, fontWeight = if (id == selectedKeyboardId) FontWeight.Bold else FontWeight.Normal)
                                    val isInst = if (id == "kde") isKdeKeyboardInstalled else isVboardInstalled
                                    if (isInst) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = Color(0xFF2E7D32).copy(alpha = 0.15f)
                                        ) {
                                            Text(
                                                "INSTALLED",
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF2E7D32)
                                            )
                                        }
                                    }
                                }
                            },
                            onClick = {
                                onSelectKeyboard(id)
                                expandedDropdown = false
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // 1. KDE Plasma Keyboard Card
            KeyboardItemRow(
                title = "KDE Plasma Keyboard",
                description = "Native Maliit / Qt touch virtual keyboard integrated into KDE Plasma Wayland.",
                isInstalled = isKdeKeyboardInstalled,
                isSelected = selectedKeyboardId == "kde",
                busy = busy,
                onInstall = onInstallKdeKeyboard
            )

            // 2. vboard Card
            KeyboardItemRow(
                title = "vboard",
                description = "Lightweight on-screen touch virtual keyboard for Wayland compositors (wvkbd/squeekboard).",
                isInstalled = isVboardInstalled,
                isSelected = selectedKeyboardId == "vboard",
                busy = busy,
                onInstall = onInstallVboard
            )
        }
    }
}

@Composable
private fun KeyboardItemRow(
    title: String,
    description: String,
    isInstalled: Boolean,
    isSelected: Boolean,
    busy: Boolean,
    onInstall: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            1.dp,
            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    if (isSelected) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        ) {
                            Text(
                                "ACTIVE",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    if (isInstalled) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF2E7D32).copy(alpha = 0.15f)
                        ) {
                            Text(
                                "INSTALLED",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF2E7D32)
                            )
                        }
                    }
                }
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Button(
                onClick = onInstall,
                enabled = !busy,
                shape = RoundedCornerShape(50),
                modifier = Modifier.widthIn(min = 90.dp)
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Install", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
