package com.example.androidautodisplay

import android.content.Context
import android.net.wifi.WifiManager

object HotspotInfoProvider {
    data class HotspotInfo(
        val ssid: String?,
        val isEnabled: Boolean?,
        val requiresManualEnable: Boolean,
        val detail: String
    )

    fun getHotspotInfo(context: Context): HotspotInfo {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return try {
            val isEnabled = tryGetApEnabled(wifiManager)
            val ssid = tryGetApSsid(wifiManager)
            if (isEnabled == true) {
                HotspotInfo(ssid, true, false, ssid ?: "Hotspot enabled")
            } else {
                HotspotInfo(ssid, false, true, "Hotspot is off")
            }
        } catch (ex: SecurityException) {
            HotspotInfo(null, null, true, "Hotspot status unavailable (system privileges required)")
        } catch (ex: ReflectiveOperationException) {
            HotspotInfo(null, null, true, "Hotspot status unavailable")
        }
    }

    private fun tryGetApEnabled(wifiManager: WifiManager): Boolean? {
        return try {
            val method = wifiManager.javaClass.getDeclaredMethod("isWifiApEnabled")
            method.isAccessible = true
            method.invoke(wifiManager) as? Boolean
        } catch (ex: Exception) {
            null
        }
    }

    private fun tryGetApSsid(wifiManager: WifiManager): String? {
        return try {
            val method = wifiManager.javaClass.getDeclaredMethod("getSoftApConfiguration")
            method.isAccessible = true
            val config = method.invoke(wifiManager)
            val ssidField = config?.javaClass?.getDeclaredField("SSID")
            ssidField?.isAccessible = true
            ssidField?.get(config) as? String
        } catch (ex: Exception) {
            null
        }
    }
}
