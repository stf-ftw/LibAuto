package com.example.androidautodisplay

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager

object WifiInfoProvider {
    fun getWifiSsid(context: Context): String {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork ?: return "not connected"
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return "not connected"
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return "not connected"
        }
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ssid = wifiManager.connectionInfo?.ssid
        if (ssid.isNullOrBlank() || ssid == "<unknown ssid>") {
            return "unknown (location permission needed)"
        }
        return ssid.trim('"')
    }
}
