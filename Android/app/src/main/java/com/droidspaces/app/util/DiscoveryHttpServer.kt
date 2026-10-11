package com.droidspaces.app.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Serves GET /discovery as plain JSON: {name, ip, services, ports}.
 *
 * Deliberately NOT bound to 0.0.0.0 -- ServerSocket is bound to the phone's own
 * Wi-Fi interface address specifically (see [ContainerManager.getPhoneLanIpAddress]),
 * so there is no socket listening on the mobile-data interface at all, not just an
 * intent to keep it LAN-only. If the phone isn't on Wi-Fi, the server refuses to
 * start rather than falling back to a broader bind address.
 *
 * Hand-rolled on a plain ServerSocket rather than a new HTTP-server dependency
 * (NanoHTTPD, Ktor, etc.) -- this endpoint is a single static GET route, and adding
 * a new Maven dependency here is a build-risk trade not worth it for that.
 */
object DiscoveryHttpServer {
    private const val TAG = "DiscoveryHttpServer"
    private var serverSocket: ServerSocket? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    val isRunning: Boolean get() = serverSocket != null

    fun start(context: Context, port: Int) {
        if (isRunning) return
        val lanIp = ContainerManager.getPhoneLanIpAddress(context)
        if (lanIp == null) {
            Log.i(TAG, "Not on Wi-Fi -- refusing to start (no LAN address to bind)")
            return
        }

        try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName(lanIp), port))
            serverSocket = socket
        } catch (e: Exception) {
            Log.w(TAG, "Failed to bind discovery server on $lanIp:$port", e)
            return
        }

        job = scope.launch {
            val appContext = context.applicationContext
            while (isRunning) {
                val client = try {
                    serverSocket?.accept()
                } catch (e: Exception) {
                    null // socket closed via stop(), or a transient accept error
                }
                if (client != null) {
                    scope.launch { handleClient(appContext, client, port) }
                } else if (!isRunning) {
                    break
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        try { serverSocket?.close() } catch (e: Exception) { /* already closed */ }
        serverSocket = null
    }

    private suspend fun handleClient(context: Context, client: Socket, port: Int) {
        client.use { sock ->
            try {
                sock.soTimeout = 3000
                val requestLine = sock.getInputStream().bufferedReader().readLine() ?: return

                val body: String
                val status: String
                if (requestLine.startsWith("GET /discovery")) {
                    body = buildDiscoveryJson(context, port)
                    status = "200 OK"
                } else {
                    body = """{"error":"not found"}"""
                    status = "404 Not Found"
                }

                val response = "HTTP/1.1 $status\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
                    "Connection: close\r\n\r\n" +
                    body
                sock.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
                sock.getOutputStream().flush()
            } catch (e: Exception) {
                Log.w(TAG, "Error handling discovery request", e)
            }
        }
    }

    private suspend fun buildDiscoveryJson(context: Context, httpPort: Int): String {
        val lanIp = ContainerManager.getPhoneLanIpAddress(context)
        val active = DiscoveryManager.computeActiveServices(context, httpPort)

        val servicesArray = JSONArray()
        val portsObject = JSONObject()
        active.forEach {
            servicesArray.put(it.label)
            portsObject.put(it.type.trimEnd('.'), it.port)
        }

        return JSONObject().apply {
            put("name", DiscoveryManager.INSTANCE_NAME)
            put("ip", lanIp ?: JSONObject.NULL)
            put("os", "Linux container")
            put("services", servicesArray)
            put("ports", portsObject)
        }.toString()
    }
}
