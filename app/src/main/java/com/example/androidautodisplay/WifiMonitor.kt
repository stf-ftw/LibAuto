package com.example.androidautodisplay

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

class WifiMonitor(private val context: Context) {
    fun isWifiConnected(): Boolean {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
