package com.example.androidautodisplay

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.util.Locale

object BluetoothBridge {
    private val macRegex = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")

    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun hasBluetoothConnectPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun needsBluetoothConnectPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false
        }
        return !hasBluetoothConnectPermission(context)
    }

    @JvmStatic
    fun nativeGetAdapterAddress(): String {
        val context = appContext ?: return ""
        if (!hasBluetoothConnectPermission(context)) {
            return ""
        }
        val direct = tryGetAdapterAddress()
        if (direct.isNotBlank()) {
            return direct
        }
        return normalizeMac(Settings.Secure.getString(context.contentResolver, "bluetooth_address"))
    }

    @JvmStatic
    fun nativeIsPhonePaired(phoneAddress: String): Boolean {
        val context = appContext ?: return false
        if (!hasBluetoothConnectPermission(context)) {
            return false
        }
        val target = normalizeMac(phoneAddress)
        if (target.isBlank()) {
            return false
        }
        return try {
            @Suppress("DEPRECATION")
            BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.any {
                normalizeMac(it.address) == target
            } == true
        } catch (_: SecurityException) {
            false
        }
    }

    @SuppressLint("HardwareIds")
    private fun tryGetAdapterAddress(): String {
        return try {
            @Suppress("DEPRECATION")
            normalizeMac(BluetoothAdapter.getDefaultAdapter()?.address)
        } catch (_: SecurityException) {
            ""
        }
    }

    private fun normalizeMac(value: String?): String {
        val normalized = value
            ?.trim()
            ?.uppercase(Locale.US)
            ?: return ""
        if (normalized == "02:00:00:00:00:00") {
            return ""
        }
        return if (macRegex.matches(normalized)) normalized else ""
    }
}
