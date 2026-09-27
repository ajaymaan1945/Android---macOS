package com.maan.connect

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MacDiscovery(private val context: Context, private val onFound: (String, Int) -> Unit, private val onState: (String) -> Unit) {
    companion object { private const val SERVICE = "_maan._tcp."; private const val PORT = 8765; private const val BEACON_PORT = 8767; private const val MAGIC = "MAAN_BEACON_V1" }
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newCachedThreadPool()
    private val running = AtomicBoolean(false)
    private var nsd: NsdManager? = null
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        onState("● Looking for your Mac")
        startNsd(); startBeaconListener(); startSubnetFallback()
    }
    fun stop() { running.set(false); runCatching { listener?.let { nsd?.stopServiceDiscovery(it) } }; listener = null; executor.shutdownNow() }

    private fun startNsd() {
        nsd = context.getSystemService(NsdManager::class.java) ?: return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(info: NsdServiceInfo) { if (info.serviceType == SERVICE) nsd?.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                override fun onServiceResolved(resolved: NsdServiceInfo) { if (running.get()) found(resolved.host.hostAddress ?: return, resolved.port) }
            }) }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { runCatching { nsd?.stopServiceDiscovery(this) } }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        listener = l
        runCatching { nsd?.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    private fun startBeaconListener() {
        executor.execute {
            runCatching {
                val ds = java.net.DatagramSocket(BEACON_PORT).apply { soTimeout = 1500; reuseAddress = true }
                val buf = ByteArray(256)
                while (running.get()) {
                    try {
                        val p = java.net.DatagramPacket(buf, buf.size); ds.receive(p)
                        val s = String(p.data, 0, p.length)
                        if (s.startsWith(MAGIC)) { val port = s.split('|').getOrNull(1)?.toIntOrNull() ?: PORT; found(p.address.hostAddress ?: continue, port) }
                    } catch (_: Exception) {}
                }
                ds.close()
            }
        }
    }

    private fun startSubnetFallback() {
        executor.execute {
            val addresses = runCatching {
                java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                    .filterIsInstance<java.net.Inet4Address>()
                    .mapNotNull { it.hostAddress }
                    .filter { !it.startsWith("127.") && !it.startsWith("169.254.") }
            }.getOrDefault(emptyList())
            for (ip in addresses) {
                val prefix = ip.substringBeforeLast('.')
                for (i in 1..254) {
                    if (!running.get()) break
                    executor.execute { probe("$prefix.$i", PORT) }
                }
            }
        }
    }

    private fun probe(ip: String, port: Int) {
        // The MAAN transport port is WebSocket. A plain TCP connect-and-close
        // makes the Python websockets server report "InvalidMessage" because
        // it never receives an HTTP Upgrade request. Perform a real WebSocket
        // handshake for discovery instead.
        try {
            val socket = Socket()
            socket.soTimeout = 500
            socket.connect(InetSocketAddress(ip, port), 250)
            socket.use { s ->
                val keyBytes = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
                val key = android.util.Base64.encodeToString(keyBytes, android.util.Base64.NO_WRAP)
                val request = buildString {
                    append("GET / HTTP/1.1\r\n")
                    append("Host: $ip:$port\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Sec-WebSocket-Key: $key\r\n")
                    append("Sec-WebSocket-Version: 13\r\n")
                    append("\r\n")
                }
                s.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
                s.getOutputStream().flush()
                val buffer = ByteArray(512)
                val count = s.getInputStream().read(buffer)
                val response = if (count > 0) String(buffer, 0, count, Charsets.US_ASCII) else ""
                if (response.startsWith("HTTP/1.1 101") || response.startsWith("HTTP/1.0 101")) {
                    found(ip, port)
                }
            }
        } catch (_: Exception) {}
    }
    private fun found(ip: String, port: Int) { if (!running.get()) return; main.post { onFound(ip, port) } }
}
