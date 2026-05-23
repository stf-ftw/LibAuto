package com.example.androidautodisplay

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
}
