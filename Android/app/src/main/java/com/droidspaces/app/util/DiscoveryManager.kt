package com.droidspaces.app.util

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * LAN discovery via Android's own NsdManager -- the platform's official mDNS/Bonjour
 * API (android.net.nsd), backed by the system's mDNSResponder/netd multicast stack.
 * No new binary, no avahi-daemon -- see the discovery feature writeup for why this
 * was chosen over bundling Avahi.
 *
 * IMPORTANT LIMITATION: NsdManager registers per-SERVICE records (SRV/PTR/TXT) under
 * whatever hostname Android's OWN mDNS responder already uses for this device -- apps
 * cannot set a literal custom A-record hostname like "droidspaces.local" through this
 * API. What DOES work correctly: browsing for _ssh._tcp (etc.) on any mDNS client will
 * find an instance named "Droidspaces" and resolve it to the right IP:port regardless
 * of what the underlying device hostname actually is. The UI surfaces this distinction
 * rather than claiming an exact hostname that may not match.
 */
object DiscoveryManager {
    private const val TAG = "DiscoveryManager"
    const val INSTANCE_NAME = "Droidspaces"

    data class AdvertisedService(
        val type: String,       // e.g. "_ssh._tcp."
        val port: Int,
        val label: String       // human-readable, for the UI service list
    )

    private var nsdManager: NsdManager? = null
    private val registrationListeners = mutableMapOf<String, NsdManager.RegistrationListener>()

    /**
     * Compute which services are currently eligible for advertisement, based on what's
     * actually enabled AND running right now across all containers -- discovery never
     * advertises a port nothing is listening on. SFTP piggybacks on the same sshd/port
     * as SSH (RFC 6335 convention: _sftp-ssh._tcp on the SSH port, not a separate one).
     */
    suspend fun computeActiveServices(context: Context, httpPort: Int): List<AdvertisedService> = withContext(Dispatchers.IO) {
        val services = mutableListOf<AdvertisedService>()
        val containers = try { ContainerManager.listContainers() } catch (e: Exception) { emptyList() }

        for (container in containers) {
            val (isRunning, _) = try { ContainerManager.checkContainerStatus(container.name) } catch (e: Exception) { Pair(false, null) }
            if (!isRunning) continue

            if (container.sshEnabled) {
                services.add(AdvertisedService("_ssh._tcp.", container.sshPort, "SSH (${container.name})"))
                services.add(AdvertisedService("_sftp-ssh._tcp.", container.sshPort, "SFTP (${container.name})"))
            }
            if (container.wirelessDisplayEnabled) {
                services.add(AdvertisedService("_droidspaces-display._tcp.", container.vncPort, "Wireless Display (${container.name})"))
            }
        }

        // The discovery HTTP endpoint itself is always advertised once discovery is
        // on at all, independent of any specific container's state.
        services.add(AdvertisedService("_http._tcp.", httpPort, "Discovery API"))
        services
    }

    /**
     * Register (or re-register, idempotently) every currently-active service.
     * Safe to call repeatedly (e.g. on network change or container start/stop) --
     * unregisters anything no longer active and adds anything newly active, rather
     * than blindly re-registering everything (NsdManager treats duplicate
     * registration of an identical service+port as an error).
     */
    suspend fun sync(context: Context) {
        if (!PreferencesManager.getInstance(context).discoveryEnabled) {
            unregisterAll()
            return
        }
        // Discovery is LAN-scoped by construction: NsdManager's underlying mDNS
        // multicast only reaches the network Android is actually bridging it onto
        // (the active Wi-Fi network in practice) -- there is no WAN path for it to
        // leak through. Still, skip registering at all when not on Wi-Fi, so nothing
        // is advertised while on mobile data even in principle.
        val onWifi = ContainerManager.getPhoneLanIpAddress(context) != null
        if (!onWifi) {
            unregisterAll()
            return
        }

        val port = PreferencesManager.getInstance(context).discoveryHttpPort
        val active = computeActiveServices(context, port)
        val activeKeys = active.map { "${it.type}:${it.port}" }.toSet()

        // Drop anything registered that's no longer active.
        val toRemove = registrationListeners.keys.filterNot { it in activeKeys }
        toRemove.forEach { unregister(it) }

        // Add anything active that isn't registered yet.
        active.forEach { service ->
            val key = "${service.type}:${service.port}"
            if (key !in registrationListeners) register(context, service, key)
        }
    }

    private fun register(context: Context, service: AdvertisedService, key: String) {
        val manager = nsdManager ?: (context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager).also { nsdManager = it }

        val info = NsdServiceInfo().apply {
            serviceName = INSTANCE_NAME
            serviceType = service.type
            port = service.port
            setAttribute("device", INSTANCE_NAME)
            setAttribute("os", "Linux container")
            setAttribute("app", "Droidspaces")
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "Registered ${service.type} on port ${service.port}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Failed to register ${service.type}:${service.port} -- error $errorCode")
                registrationListeners.remove(key)
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "Unregistered ${service.type}:${service.port}")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Failed to unregister ${service.type}:${service.port} -- error $errorCode")
            }
        }

        try {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            registrationListeners[key] = listener
        } catch (e: Exception) {
            Log.w(TAG, "registerService threw for ${service.type}:${service.port}", e)
        }
    }

    private fun unregister(key: String) {
        val listener = registrationListeners.remove(key) ?: return
        try {
            nsdManager?.unregisterService(listener)
        } catch (e: Exception) {
            Log.w(TAG, "unregisterService threw for $key", e)
        }
    }

    /** Stop advertising everything -- called when discovery is turned off. */
    fun unregisterAll() {
        registrationListeners.keys.toList().forEach { unregister(it) }
    }
}
