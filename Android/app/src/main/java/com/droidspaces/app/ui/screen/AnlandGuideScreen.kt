package com.droidspaces.app.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.droidspaces.app.R

private const val ANLAND_RELEASES_URL = "https://github.com/superturtlee/anland/releases/latest"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnlandGuideScreen(
    onNavigateBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Anland Wayland Setup",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AnlandGuideContent()
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun AnlandGuideDialog(
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.DesktopWindows,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Anland Setup Guide",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    AnlandGuideContent()
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Button(onClick = onDismiss) {
                        Text("Got it")
                    }
                }
            }
        }
    }
}

@Composable
private fun AnlandGuideContent() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    // Overview Header Card with clear contrast
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = "Hardware-Accelerated Wayland on Android",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = "Anland provides native multi-window Wayland display forwarding using virtual DRM & GPU buffer sharing. Follow these steps to set up and run Wayland desktop environments on your device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // Step 1: Install Companion App
    GuideStepCard(
        stepNumber = 1,
        title = "Install Anland Consumer App on Android",
        description = "Download and install the Anland consumer APK (app-debug.apk). This application receives GPU buffers and renders Wayland desktop windows directly on Android.",
        actionButton = {
            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(ANLAND_RELEASES_URL))
                    context.startActivity(intent)
                }
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Get Anland APK", style = MaterialTheme.typography.labelLarge)
            }
        }
    )

    // Step 2: Install Desktop Backend in Container
    GuideStepCard(
        stepNumber = 2,
        title = "Install Anland Desktop in Container",
        description = "Inside Container Details, tap 'Install Anland KDE' or run the one-click installer script in the container terminal:",
        codeSnippet = "curl -fLO https://raw.githubusercontent.com/Goldzxcbug/Droidspaces-rootfs-KDE-builder/main/scripts/install-anland-kde.sh && chmod +x install-anland-kde.sh && ./install-anland-kde.sh",
        onCopy = {
            clipboard.setText(AnnotatedString("curl -fLO https://raw.githubusercontent.com/Goldzxcbug/Droidspaces-rootfs-KDE-builder/main/scripts/install-anland-kde.sh && chmod +x install-anland-kde.sh && ./install-anland-kde.sh"))
        }
    )

    // Step 3: Container Settings
    GuideStepCard(
        stepNumber = 3,
        title = "Configure Container Settings",
        description = "In container options:\n• Enable 'GPU Access' and 'Anland Display'\n• Disable 'Configure Termux:X11' (prevents display variable conflicts)\n• Disable 'Configure PulseAudio' (Anland manages audio independently)\n• In Privileged Mode, ensure 'nocaps' and 'noseccomp' are enabled"
    )

    // Step 4: Setup Environment Variables
    GuideStepCard(
        stepNumber = 4,
        title = "Setup Environment Variables",
        description = "Tap 'Setup Environment' in Container Details to automatically populate /etc/environment, or configure manually:",
        codeSnippet = "WAYLAND_DISPLAY=wayland-0\nDISPLAY=:0\nQT_QPA_PLATFORM=wayland\nQT_AUTO_SCREEN_SCALE_FACTOR=1\nGDK_BACKEND=wayland,x11\nCLUTTER_BACKEND=wayland\nSDL_VIDEODRIVER=wayland\nXDG_SESSION_TYPE=wayland\nANLAND=1\nANLAND_SOCKET=/run/display.sock\nANLAND_DRM_DEVICE=/dev/dri/renderD128\nKWIN_IM_SHOW_ALWAYS=1\nKWIN_WAYLAND_VIRTUAL_KEYBOARD=1\nQT_IM_MODULE=maliit",
        onCopy = {
            clipboard.setText(AnnotatedString("WAYLAND_DISPLAY=wayland-0\nDISPLAY=:0\nQT_QPA_PLATFORM=wayland\nQT_AUTO_SCREEN_SCALE_FACTOR=1\nGDK_BACKEND=wayland,x11\nCLUTTER_BACKEND=wayland\nSDL_VIDEODRIVER=wayland\nXDG_SESSION_TYPE=wayland\nANLAND=1\nANLAND_SOCKET=/run/display.sock\nANLAND_DRM_DEVICE=/dev/dri/renderD128\nKWIN_IM_SHOW_ALWAYS=1\nKWIN_WAYLAND_VIRTUAL_KEYBOARD=1\nQT_IM_MODULE=maliit"))
        }
    )

    // Step 5: Start Wayland Desktop (KDE or Niri)
    GuideStepCard(
        stepNumber = 5,
        title = "Start Wayland Compositor (KDE or Niri)",
        description = "Start the container, then launch your chosen compositor in the container terminal:\n• For KDE Plasma: startplasma-wayland\n• For Niri: niri --anland",
        codeSnippet = "startplasma-wayland",
        onCopy = {
            clipboard.setText(AnnotatedString("startplasma-wayland"))
        }
    )

    // Step 6: Troubleshooting & Compatibility
    GuideStepCard(
        stepNumber = 6,
        title = "Troubleshooting & Compatibility Fixes",
        description = "• Snapdragon 8 Gen 2 / Adreno 7xx: If you experience graphical flickering or artifacts, write this to /etc/environment (or check the box in 'Setup Environment'):\n  FD_DEV_FEATURES=enable_tp_ubwc_flag_hint=1\n\n• SELinux: If display buffers fail to forward, test with SELinux Permissive mode.\n\n• Virtual DRM Daemon: For custom rootfs setups, install virtual-drm-daemon.zip as a Magisk / KernelSU / APatch module.",
        codeSnippet = "FD_DEV_FEATURES=enable_tp_ubwc_flag_hint=1",
        onCopy = {
            clipboard.setText(AnnotatedString("FD_DEV_FEATURES=enable_tp_ubwc_flag_hint=1"))
        }
    )
}

@Composable
private fun GuideStepCard(
    stepNumber: Int,
    title: String,
    description: String,
    codeSnippet: String? = null,
    onCopy: (() -> Unit)? = null,
    actionButton: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(24.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = stepNumber.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (codeSnippet != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = codeSnippet,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        if (onCopy != null) {
                            IconButton(
                                onClick = onCopy,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = "Copy",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (actionButton != null) {
                actionButton()
            }
        }
    }
}
