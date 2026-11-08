package dev.tvapkpush.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.util.Log
import android.system.ErrnoException
import android.system.OsConstants
import dev.tvapkpush.adb.AdbProbe
import dev.tvapkpush.core.TvDevice
import java.io.Closeable
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

data class DiscoveredDevice(val name: String, val host: String, val port: Int, val requiresPairing: Boolean = false) {
    val address: String get() = if (host.contains(':')) "[$host]:$port" else "$host:$port"
}

data class DiscoveryState(val searching: Boolean, val devices: List<DiscoveredDevice>, val message: String = "")

@Suppress("DEPRECATION")
class DeviceDiscovery(context: Context, private val savedDevices: List<TvDevice> = emptyList(),
    private val changed: (DiscoveryState) -> Unit) : Closeable {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val probes = Executors.newFixedThreadPool(32) { Thread(it, "adb-discovery") }
    private val advertisedProbes = Executors.newFixedThreadPool(4) { Thread(it, "adb-advertised-discovery") }
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val systemRoutedNetworks = ConcurrentHashMap.newKeySet<Network>()
    private val requested = ConcurrentHashMap.newKeySet<String>()
    private val found = linkedMapOf<String, DiscoveredDevice>()
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private data class Resolution(val service: NsdServiceInfo, val attempt: Int = 0)
    private val resolveQueue = ArrayDeque<Resolution>()
    private val seenServices = mutableSetOf<String>()
    private var resolving: NsdManager.ResolveListener? = null
    private var resolveTimeout: Runnable? = null
    private val pending = AtomicInteger()
    private var started = false
    @Volatile private var closed = false
    private var networks = emptyList<Network>()
    private val finish = Runnable { finishSearch() }
    private val idleCheck = object : Runnable {
        override fun run() {
            if (closed) return
            if (pending.get() == 0 && resolving == null && resolveQueue.isEmpty()) finishSearch()
            else main.postDelayed(this, 1000)
        }
    }

    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(!started)
        started = true
        networks = (listOfNotNull(connectivity.activeNetwork) + connectivity.allNetworks).distinct().filter { candidate ->
            connectivity.getNetworkCapabilities(candidate)?.let {
                (it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                    !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } == true
        }
        if (networks.isEmpty()) {
            finishSearch("请连接 Wi-Fi 或有线局域网")
            return
        }
        changed(DiscoveryState(true, emptyList()))
        networks.forEach { lan ->
            savedDevices.forEach { device ->
                val host = runCatching { InetAddress.getByName(device.host) }.getOrNull()
                if (host != null && validHost(host, lan)) probe(lan, device.host, device.port, device.name, priority = true)
            }
            val addresses = connectivity.getLinkProperties(lan)?.linkAddresses.orEmpty().filter { it.address is Inet4Address }
            val ownHosts = addresses.map { it.address.hostAddress.orEmpty() }.toSet()
            val localBlocks = ownHosts.map { it.substringBeforeLast('.') }.toSet()
            addresses.flatMap { LanAddresses.nearby(it.address as Inet4Address, it.prefixLength) }.distinct().filterNot { it in ownHosts }.forEach {
                probe(lan, it, 5555, retry = it.substringBeforeLast('.') in localBlocks)
            }
        }
        listOf("_adb._tcp.", "_adb-tls-connect._tcp.", "_adb-tls-pairing._tcp.").forEach { type ->
            if (Build.VERSION.SDK_INT >= 33) networks.forEach { discover(type, it) } else discover(type, null)
        }
        main.postDelayed(idleCheck, 15_000)
        main.postDelayed(finish, 45_000)
    }

    private fun discover(type: String, lan: Network?, attempt: Int = 0) {
        if (closed) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                if (closed) runCatching { nsd.stopServiceDiscovery(this) }
            }
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w("APKPush", "Discovery stop failed: code=$errorCode")
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w("APKPush", "Discovery start failed: code=$errorCode")
                runCatching { nsd.stopServiceDiscovery(this) }
                if (attempt == 0) main.postDelayed({ discover(type, lan, 1) }, 1000)
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                main.post {
                    if (!closed && seenServices.size < 64 && seenServices.add("$lan/${serviceInfo.serviceType}/${serviceInfo.serviceName}")) {
                        resolveQueue.addLast(Resolution(serviceInfo))
                        resolveNext()
                    }
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
        }
        listeners.add(listener)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33 && lan != null)
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, lan, { main.post(it) }, listener)
            else nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        }
            .onFailure { Log.w("APKPush", "Discovery unavailable: type=${it.javaClass.simpleName}") }
    }

    private fun resolveNext() {
        if (closed || resolving != null || resolveQueue.isEmpty()) return
        val request = resolveQueue.removeFirst()
        val listener = object : NsdManager.ResolveListener {
            private fun complete(info: NsdServiceInfo?) {
                main.post {
                    if (closed || resolving !== this) return@post
                    resolveTimeout?.let(main::removeCallbacks)
                    resolveTimeout = null
                    resolving = null
                    if (info == null) {
                        if (request.attempt == 0) resolveQueue.addLast(request.copy(attempt = 1))
                    } else {
                        val host = info.host
                        val advertisedNetwork = if (Build.VERSION.SDK_INT >= 33) info.network else null
                        val lan = networks.firstOrNull { (advertisedNetwork == null || advertisedNetwork == it) && host != null && validHost(host, it) }
                        if (host != null && lan != null && info.port in 1..65535) {
                            val address = host.hostAddress.orEmpty()
                            val name = info.serviceName.filter { !it.isISOControl() }.take(60)
                            if (info.serviceType.contains("_adb-tls-")) {
                                publish(DiscoveredDevice(name, address, info.port, requiresPairing = true))
                            } else probe(lan, address, info.port, name, priority = true)
                        }
                    }
                    main.postDelayed({ resolveNext() }, if (info == null) 200 else 0)
                }
            }
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = complete(null)
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) = complete(serviceInfo)
        }
        resolving = listener
        val timeout = Runnable {
            if (!closed && resolving === listener) {
                if (Build.VERSION.SDK_INT >= 34) runCatching { nsd.stopServiceResolution(listener) }
                listener.onResolveFailed(request.service, NsdManager.FAILURE_INTERNAL_ERROR)
            }
        }
        resolveTimeout = timeout
        main.postDelayed(timeout, 2500)
        try { nsd.resolveService(request.service, listener) } catch (error: RuntimeException) {
            Log.w("APKPush", "Service resolution failed: type=${error.javaClass.simpleName}")
            listener.onResolveFailed(request.service, NsdManager.FAILURE_INTERNAL_ERROR)
        }
    }

    private fun validHost(address: InetAddress, lan: Network): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isMulticastAddress) return false
        return connectivity.getLinkProperties(lan)?.routes.orEmpty().any { !it.isDefaultRoute && !it.hasGateway() && it.matches(address) }
    }

    private fun probe(lan: Network, host: String, port: Int, name: String = "", priority: Boolean = false, retry: Boolean = true) {
        if (closed || !requested.add("$lan/$host:$port")) return
        submitProbe(lan, host, port, name, priority, retry, 0)
    }

    private fun submitProbe(lan: Network, host: String, port: Int, name: String, priority: Boolean, retry: Boolean, attempt: Int) {
        if (closed) return
        pending.incrementAndGet()
        try {
            (if (priority) advertisedProbes else probes).execute {
                try {
                    if (closed) return@execute
                    val socket = probeSocket(lan)
                    socket.use {
                        sockets.add(socket)
                        try {
                            if (!closed) AdbProbe.inspect(socket, host, port, if (attempt > 0) 1600 else 800)?.let { response ->
                                main.post { publish(DiscoveredDevice(name.ifBlank { response.name }, host, port, response.requiresPairing)) }
                            }
                        } catch (_: SocketTimeoutException) {
                            if (retry && attempt == 0 && !closed) {
                                main.post { submitProbe(lan, host, port, name, priority, retry, 1) }
                            }
                        } finally { sockets.remove(socket) }
                    }
                } catch (_: Exception) {
                    // 未开放端口和非 ADB 服务不进入发现列表。
                } finally { pending.decrementAndGet() }
            }
        } catch (_: RejectedExecutionException) { pending.decrementAndGet() }
    }

    private fun probeSocket(lan: Network): Socket {
        if (lan in systemRoutedNetworks) return Socket()
        try {
            return lan.socketFactory.createSocket()
        } catch (error: SocketException) {
            val errno = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<ErrnoException>().firstOrNull()?.errno
            if (errno != OsConstants.EPERM && errno != OsConstants.EACCES) throw error
            // VPN 或系统策略禁止绑定网络时，由系统为局域网目标选择路由。
            if (systemRoutedNetworks.add(lan)) Log.i("APKPush", "Network binding denied; using system routing for LAN discovery")
            return Socket()
        }
    }

    private fun publish(device: DiscoveredDevice) {
        if (closed) return
        found[device.address] = device
        changed(DiscoveryState(true, found.values.toList()))
    }

    private fun finishSearch(message: String = "") {
        if (closed) return
        close()
        changed(DiscoveryState(false, found.values.toList(), message))
    }

    override fun close() {
        closed = true
        main.removeCallbacks(finish)
        main.removeCallbacks(idleCheck)
        resolveTimeout?.let(main::removeCallbacks)
        if (Build.VERSION.SDK_INT >= 34) resolving?.let { runCatching { nsd.stopServiceResolution(it) } }
        resolving = null
        listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
        listeners.clear()
        resolveQueue.clear()
        probes.shutdownNow()
        advertisedProbes.shutdownNow()
        sockets.forEach { runCatching { it.close() } }
    }
}
