package com.example.androidautodisplay

import android.content.Context
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object HeadunitServerScanner {

    const val DEFAULT_AA_PORT = 5277
    const val ALT_AA_PORT = 5288

    private val COMMON_HOTSPOT_IPS = listOf(
        "192.168.43.1",
        "192.168.44.1",
        "192.168.49.1",
        "192.168.50.1",
        "192.168.225.1",
        "172.20.10.1" // iOS hotspot standard IP
    )

    data class ScanResult(val ip: String, val port: Int)

    fun findServerIp(
        context: Context,
        targetPort: Int = DEFAULT_AA_PORT,
        preferredIp: String? = null,
        onProgress: (String) -> Unit = {}
    ): String? {
        return findServer(context, targetPort, preferredIp, onProgress)?.ip
    }

    /**
     * Fast auto-detect the IP and port running Android Auto Headunit Server.
     * Checks gateway, standard hotspot IPs, ARP table, and scans the local /24 subnet.
     */
    fun findServer(
        context: Context,
        targetPort: Int = DEFAULT_AA_PORT,
        preferredIp: String? = null,
        onProgress: (String) -> Unit = {}
    ): ScanResult? {
        val portsToCheck = if (targetPort == DEFAULT_AA_PORT) {
            listOf(DEFAULT_AA_PORT, ALT_AA_PORT)
        } else {
            listOf(targetPort)
        }

        // 1. If preferred / previously saved IP is provided, test it first
        if (!preferredIp.isNullOrBlank() && preferredIp != "127.0.0.1") {
            for (p in portsToCheck) {
                onProgress("Checking last known address $preferredIp:$p...")
                if (isPortOpen(preferredIp, p, timeoutMs = 350)) {
                    Thread.sleep(300)
                    return ScanResult(preferredIp, p)
                }
            }
        }

        // 2. Check DHCP Gateway (when connected to phone's mobile hotspot, phone is the gateway!)
        val gateway = NetworkInfoProvider.getGatewayAddress(context)
        if (gateway.isNotBlank() && gateway != preferredIp) {
            for (p in portsToCheck) {
                onProgress("Checking gateway $gateway:$p...")
                if (isPortOpen(gateway, p, timeoutMs = 350)) {
                    Thread.sleep(300)
                    return ScanResult(gateway, p)
                }
            }
        }

        // 3. Check common Android/iOS mobile hotspot gateway IPs
        for (hotspotIp in COMMON_HOTSPOT_IPS) {
            if (hotspotIp != gateway && hotspotIp != preferredIp) {
                for (p in portsToCheck) {
                    if (isPortOpen(hotspotIp, p, timeoutMs = 250)) {
                        Thread.sleep(300)
                        return ScanResult(hotspotIp, p)
                    }
                }
            }
        }

        // 4. Check ARP table (/proc/net/arp) in case this device is hotspot host and phone is client
        val arpIps = readArpTableIps()
        for (arpIp in arpIps) {
            if (arpIp != gateway && arpIp != preferredIp) {
                for (p in portsToCheck) {
                    onProgress("Checking connected client $arpIp:$p...")
                    if (isPortOpen(arpIp, p, timeoutMs = 300)) {
                        Thread.sleep(300)
                        return ScanResult(arpIp, p)
                    }
                }
            }
        }

        // 5. Scan local /24 subnet in parallel (e.g. 192.168.1.1 - 192.168.1.254)
        val myIp = NetworkInfoProvider.getWifiIpAddress()
        if (!myIp.isNullOrBlank() && myIp.contains(".")) {
            val prefix = myIp.substringBeforeLast(".") + "."
            val myHostNum = myIp.substringAfterLast(".").toIntOrNull() ?: -1

            val candidateIps = (1..254)
                .filter { it != myHostNum }
                .map { "$prefix$it" }

            for (p in portsToCheck) {
                onProgress("Scanning network ($prefix*:$p)...")
                val found = scanIpsInParallel(candidateIps, p, timeoutMs = 400)
                if (found != null) {
                    Thread.sleep(300)
                    return ScanResult(found, p)
                }
            }
        }

        return null
    }

    private fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun scanIpsInParallel(ips: List<String>, port: Int, timeoutMs: Int): String? {
        val poolSize = Runtime.getRuntime().availableProcessors().coerceIn(16, 32)
        val executor = Executors.newFixedThreadPool(poolSize)
        val completionService = ExecutorCompletionService<String?>(executor)

        try {
            for (ip in ips) {
                completionService.submit {
                    if (isPortOpen(ip, port, timeoutMs)) ip else null
                }
            }

            var processed = 0
            while (processed < ips.size) {
                val future = completionService.poll(1500, TimeUnit.MILLISECONDS) ?: break
                processed++
                val result = future.get()
                if (result != null) {
                    executor.shutdownNow()
                    return result
                }
            }
        } catch (_: Exception) {
        } finally {
            executor.shutdownNow()
        }
        return null
    }

    private fun readArpTableIps(): List<String> {
        val result = mutableListOf<String>()
        try {
            val file = File("/proc/net/arp")
            if (file.exists() && file.canRead()) {
                file.forEachLine { line ->
                    val tokens = line.split("\\s+".toRegex())
                    if (tokens.isNotEmpty() && tokens[0].matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$"))) {
                        val ip = tokens[0]
                        if (!result.contains(ip) && ip != "0.0.0.0") {
                            result.add(ip)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return result
    }
}
