package com.example.androidautodisplay

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkInfoProvider {
    fun getDeviceIpv4Addresses(): List<String> {
        val result = mutableListOf<String>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return result
        for (netIf in interfaces) {
            val addresses = netIf.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    result.add(address.hostAddress)
                }
            }
        }
        return result
    }

    fun getWifiIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (netIf in interfaces) {
                if (netIf.name.startsWith("wlan", ignoreCase = true) ||
                    netIf.name.startsWith("eth", ignoreCase = true)
                ) {
                    val addresses = netIf.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        if (address is Inet4Address && !address.isLoopbackAddress) {
                            return address.hostAddress
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return getDeviceIpv4Addresses().firstOrNull {
            it.startsWith("192.168.") || it.startsWith("172.") || it.startsWith("10.")
        }
    }

    fun getGatewayAddress(context: Context): String {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val dhcp = wifiManager?.dhcpInfo
            if (dhcp != null && dhcp.gateway != 0) {
                val g = dhcp.gateway
                return "%d.%d.%d.%d".format(
                    g and 0xff,
                    (g shr 8) and 0xff,
                    (g shr 16) and 0xff,
                    (g shr 24) and 0xff
                )
            }
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork
            val lp = network?.let { cm.getLinkProperties(it) }
            val routeGateway = lp?.routes?.firstOrNull { it.isDefaultRoute && it.hasGateway() }?.gateway?.hostAddress
            if (!routeGateway.isNullOrBlank()) {
                return routeGateway
            }
        } catch (_: Exception) {
        }
        return "192.168.43.1"
    }
}
