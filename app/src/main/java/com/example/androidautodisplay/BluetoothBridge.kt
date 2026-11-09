package com.example.androidautodisplay

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File
import java.security.MessageDigest
import java.util.Locale

object BluetoothBridge {
    private val macRegex = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var overrideAdapterMac: String? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun setAdapterAddressOverride(mac: String) {
        val norm = normalizeMac(mac)
        if (norm.isNotBlank()) {
            overrideAdapterMac = norm
        }
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

    fun hasAllBluetoothPermissions(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED
    }

    fun getRequiredBluetoothPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            emptyArray()
        }
    }

    fun needsBluetoothConnectPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false
        }
        return !hasAllBluetoothPermissions(context)
    }

    @JvmStatic
    fun nativeGetAdapterAddress(): String {
        overrideAdapterMac?.let { if (it.isNotBlank()) return it }
        val context = appContext

        // 1. Try direct BluetoothAdapter address
        val direct = tryGetAdapterAddress()
        if (direct.isNotBlank()) {
            return direct
        }

        // 2. Try hidden API on BluetoothAdapter.mService.getAddress()
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter != null) {
                val field = adapter.javaClass.getDeclaredField("mService").apply { isAccessible = true }
                val service = field.get(adapter)
                if (service != null) {
                    val method = service.javaClass.getMethod("getAddress").apply { isAccessible = true }
                    val addr = normalizeMac(method.invoke(service) as? String)
                    if (addr.isNotBlank()) {
                        return addr
                    }
                }
            }
        } catch (_: Throwable) {}

        // 3. Try Settings.Secure
        if (context != null) {
            try {
                val sec = normalizeMac(Settings.Secure.getString(context.contentResolver, "bluetooth_address"))
                if (sec.isNotBlank()) {
                    return sec
                }
            } catch (_: Throwable) {}
        }

        // 4. Try Linux sysfs / system properties
        try {
            val hci = normalizeMac(File("/sys/class/bluetooth/hci0/address").readText().trim())
            if (hci.isNotBlank()) {
                return hci
            }
        } catch (_: Throwable) {}

        try {
            val getProp = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            for (key in listOf("persist.service.bdroid.bdaddr", "ro.boot.btmacaddr", "persist.sys.bt.address")) {
                val prop = normalizeMac(getProp.invoke(null, key) as? String)
                if (prop.isNotBlank()) {
                    return prop
                }
            }
        } catch (_: Throwable) {}

        // 5. Use persistent deterministic MAC so AASDK can always advertise Bluetooth channel
        if (context != null) {
            val prefs = context.getSharedPreferences("libauto_bt", Context.MODE_PRIVATE)
            val cached = normalizeMac(prefs.getString("adapter_mac", null))
            if (cached.isNotBlank()) {
                return cached
            }

            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "libauto_headunit"
            val md5 = MessageDigest.getInstance("MD5").digest(androidId.toByteArray(Charsets.UTF_8))
            val generated = "02:%02X:%02X:%02X:%02X:%02X".format(
                Locale.US,
                md5[0], md5[1], md5[2], md5[3], md5[4]
            )
            prefs.edit().putString("adapter_mac", generated).apply()
            return generated
        }

        return "02:11:22:33:44:55"
    }

    /**
     * Always returns true to ensure AASDK responds with already_paired = true and
     * BluetoothPairingStatus::OK to Android Auto.
     * This prevents Android Auto on the phone from aborting projection due to Bluetooth pairing mismatch.
     */
    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun nativeIsPhonePaired(phoneAddress: String): Boolean {
        return true
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
