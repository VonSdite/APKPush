package dev.tvapkpush

import android.app.Instrumentation
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dev.tvapkpush.adb.AdbPacket
import dev.tvapkpush.discovery.DeviceDiscovery
import dev.tvapkpush.core.TvDevice
import java.net.Inet4Address
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

object DiscoverySmokeCheck {
    fun run(instrumentation: Instrumentation) {
        checkSavedRetry(instrumentation)
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "Saved custom port timeout retry passed.\n") })
        val nsd = instrumentation.targetContext.getSystemService(NsdManager::class.java)
        val registered = CountDownLatch(2)
        val detected = CountDownLatch(2)
        val failure = AtomicReference<String?>()
        val callbacks = AtomicInteger()
        var legacyFound = false
        var tlsFound = false
        var discovery: DeviceDiscovery? = null
        val registrations = mutableListOf<NsdManager.RegistrationListener>()
        val worker = Executors.newSingleThreadExecutor()
        ServerSocket(0).use { legacy ->
            ServerSocket(0).use { tls ->
                fun advertise(type: String, name: String, portNumber: Int) {
                    val listener = object : NsdManager.RegistrationListener {
                        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) { registered.countDown() }
                        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            failure.set("NSD registration failed: code=$errorCode")
                            registered.countDown()
                        }
                        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
                        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                    }
                    registrations.add(listener)
                    nsd.registerService(NsdServiceInfo().apply {
                        serviceName = name
                        serviceType = type
                        port = portNumber
                    }, NsdManager.PROTOCOL_DNS_SD, listener)
                }
                try {
                    legacy.soTimeout = 18_000
                    val handshake = worker.submit<Boolean> {
                        legacy.accept().use { socket ->
                            socket.soTimeout = 3_000
                            check(AdbPacket.read(socket.getInputStream()).command == "CNXN")
                            AdbPacket("AUTH", 1, 0, ByteArray(20) { 1 }).write(socket.getOutputStream())
                            check(socket.getInputStream().read() == -1) { "Discovery sent authentication data" }
                        }
                        true
                    }
                    advertise("_adb._tcp.", "APK Push discovery check", legacy.localPort)
                    advertise("_adb-tls-connect._tcp.", "APK Push TLS check", tls.localPort)
                    check(registered.await(5, TimeUnit.SECONDS)) { "NSD registration timed out" }
                    check(failure.get() == null) { failure.get().orEmpty() }
                    instrumentation.runOnMainSync {
                        discovery = DeviceDiscovery(instrumentation.targetContext) { state ->
                            callbacks.incrementAndGet()
                            if (!legacyFound && state.devices.any { it.port == legacy.localPort && !it.requiresPairing }) {
                                legacyFound = true
                                detected.countDown()
                            }
                            if (!tlsFound && state.devices.any { it.port == tls.localPort && it.requiresPairing }) {
                                tlsFound = true
                                detected.countDown()
                            }
                        }.also { it.start() }
                    }
                    check(detected.await(15, TimeUnit.SECONDS)) { "ADB service discovery timed out: legacy=$legacyFound, tls=$tlsFound" }
                    check(handshake.get(3, TimeUnit.SECONDS))
                    instrumentation.runOnMainSync { discovery?.close() }
                    val stoppedCount = callbacks.get()
                    Thread.sleep(600)
                    check(callbacks.get() == stoppedCount) { "Discovery callback received after close" }
                } finally {
                    instrumentation.runOnMainSync { discovery?.close() }
                    registrations.forEach { runCatching { nsd.unregisterService(it) } }
                    worker.shutdownNow()
                }
            }
        }
    }

    private fun checkSavedRetry(instrumentation: Instrumentation) {
        val context = instrumentation.targetContext
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val lan = connectivity.allNetworks.first { network ->
            connectivity.getNetworkCapabilities(network)?.let {
                (it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                    !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } == true
        }
        val host = connectivity.getLinkProperties(lan)!!.linkAddresses.first { it.address is Inet4Address }.address.hostAddress!!
        val detected = CountDownLatch(1)
        val attempts = AtomicInteger()
        val worker = Executors.newSingleThreadExecutor()
        var discovery: DeviceDiscovery? = null
        ServerSocket(0).use { server ->
            server.soTimeout = 12_000
            val handshake = worker.submit<Boolean> {
                repeat(2) { attempt ->
                    server.accept().use { socket ->
                        attempts.incrementAndGet()
                        socket.soTimeout = 3000
                        check(AdbPacket.read(socket.getInputStream()).command == "CNXN")
                        Thread.sleep(1000)
                        if (attempt == 1) {
                            AdbPacket("AUTH", 1, 0, ByteArray(20) { 1 }).write(socket.getOutputStream())
                            check(socket.getInputStream().read() == -1) { "Saved-device discovery sent authentication data" }
                        }
                    }
                }
                true
            }
            try {
                instrumentation.runOnMainSync {
                    discovery = DeviceDiscovery(context, listOf(TvDevice("fixture", "Slow saved device", host, server.localPort))) { state ->
                        if (state.devices.any { it.host == host && it.port == server.localPort }) detected.countDown()
                    }.also { it.start() }
                }
                check(detected.await(12, TimeUnit.SECONDS)) { "Saved custom port was not found after timeout retry: attempts=${attempts.get()}" }
                check(attempts.get() == 2 && handshake.get(3, TimeUnit.SECONDS))
            } finally {
                instrumentation.runOnMainSync { discovery?.close() }
                worker.shutdownNow()
            }
        }
    }
}
