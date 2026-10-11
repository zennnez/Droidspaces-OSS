package com.droidspaces.app.ui.screen

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.droidspaces.app.util.DiscoveryHttpServer
import com.droidspaces.app.util.DiscoveryManager
import com.droidspaces.app.util.DiscoveryQrCode
import com.droidspaces.app.util.PreferencesManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Network Discovery" settings screen: enable/disable LAN mDNS advertisement,
 * show what's currently being advertised, and share/QR the connection info.
 *
 * See DiscoveryManager's KDoc for the "droidspaces.local" hostname caveat this
 * screen deliberately reflects rather than glossing over -- the address shown
 * is the phone's real LAN IP (what actually works today), with the mDNS
 * instance name displayed separately as what other-name discovery clients see.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkDiscoveryScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { PreferencesManager.getInstance(context) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var enabled by remember { mutableStateOf(prefs.discoveryEnabled) }
    var services by remember { mutableStateOf<List<DiscoveryManager.AdvertisedService>>(emptyList()) }
    var lanIp by remember { mutableStateOf<String?>(null) }
    var showQrFor by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    suspend fun refresh() {
        lanIp = com.droidspaces.app.util.ContainerManager.getPhoneLanIpAddress(context)
        services = if (enabled) DiscoveryManager.computeActiveServices(context, prefs.discoveryHttpPort) else emptyList()
    }

    // Poll periodically while this screen is open -- containers/networks can
    // change state (start/stop, Wi-Fi toggle) without the user touching this
    // screen at all, and the whole point is to show that live.
    LaunchedEffect(enabled) {
        while (true) {
            refresh()
            delay(4000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network Discovery") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // --- Enable toggle ---------------------------------------------
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Enabled", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Advertise this device on the local network via mDNS",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { value ->
                            enabled = value
                            prefs.discoveryEnabled = value
                            scope.launch {
                                busy = true
                                if (value) {
                                    DiscoveryHttpServer.start(context, prefs.discoveryHttpPort)
                                } else {
                                    DiscoveryHttpServer.stop()
                                }
                                DiscoveryManager.sync(context)
                                refresh()
                                busy = false
                            }
                        }
                    )
                }
            }

            if (!enabled) {
                Text(
                    "Off by default. When on, this device becomes discoverable to other devices on the SAME Wi-Fi network only -- see Security notes below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (enabled) {
                // --- Device / Address --------------------------------------
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Device name", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(DiscoveryManager.INSTANCE_NAME, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Address", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            lanIp ?: "Not on Wi-Fi",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "mDNS clients will find this device as \"${DiscoveryManager.INSTANCE_NAME}\" when browsing " +
                                "_ssh._tcp etc. -- the literal hostname \"droidspaces.local\" isn't guaranteed to resolve " +
                                "on every client; the IP above always works.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // --- Services -----------------------------------------------
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Services", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(8.dp))
                        if (services.isEmpty()) {
                            Text(
                                "Nothing active right now -- enable and start SSH or Wireless Display on a running container to advertise it here.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            services.forEach { service ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("${service.label} -- port ${service.port}", modifier = Modifier.weight(1f))
                                    if (lanIp != null) {
                                        val uri = discoveryUriFor(service, lanIp!!)
                                        if (uri != null) {
                                            IconButton(onClick = { showQrFor = uri }) {
                                                Icon(Icons.Default.QrCode, contentDescription = "Show QR")
                                            }
                                            IconButton(onClick = {
                                                clipboard.setText(AnnotatedString(uri))
                                                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                                            }) {
                                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // --- Discovery API --------------------------------------------
                if (lanIp != null) {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Discovery API", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "GET http://$lanIp:${prefs.discoveryHttpPort}/discovery",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                            Text(
                                "Returns {name, ip, services, ports} as JSON -- served only on the Wi-Fi interface, not reachable from mobile data.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // --- Security notes ---------------------------------------------
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Security", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "- Announces only while on Wi-Fi -- off on mobile data automatically.\n" +
                            "- No router/UPnP configuration is ever attempted -- discovery only advertises, it never opens a WAN path.\n" +
                            "- Off by default; turning it off here immediately stops all advertisement.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    showQrFor?.let { content ->
        AlertDialog(
            onDismissRequest = { showQrFor = null },
            title = { Text("Scan to connect") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    val bitmap = remember(content) { DiscoveryQrCode.generate(content) }
                    if (bitmap != null) {
                        Image(bitmap = bitmap.asImageBitmap(), contentDescription = "QR code")
                    } else {
                        Text("Couldn't generate QR code")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(content, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
            },
            confirmButton = {
                TextButton(onClick = { showQrFor = null }) { Text("Close") }
            }
        )
    }
}

/** Build the connect-URI a QR code / copy button should carry for a given advertised service. */
private fun discoveryUriFor(service: DiscoveryManager.AdvertisedService, lanIp: String): String? {
    return when {
        service.type.startsWith("_ssh._tcp") -> "ssh://$lanIp:${service.port}"
        service.type.startsWith("_sftp-ssh._tcp") -> "sftp://$lanIp:${service.port}"
        service.type.startsWith("_http._tcp") -> "http://$lanIp:${service.port}"
        service.type.startsWith("_droidspaces-display._tcp") -> "vnc://$lanIp:${service.port}"
        else -> null
    }
}
