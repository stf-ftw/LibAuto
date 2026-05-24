package com.example.androidautodisplay

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.io.InputStream
import java.io.OutputStream
import java.net.NetworkInterface
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class WirelessAaController(
    private val context: Context,
    private val logger: (String) -> Unit
) {
    data class Snapshot(
        val status: String,
        val details: String
    )

    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private val serverSockets = mutableListOf<BluetoothServerSocket>()
    private var clientSocket: BluetoothSocket? = null
    private val workers = mutableListOf<Thread>()
    @Volatile private var currentSnapshot = Snapshot("stopped", "Wireless Android Auto stopped")

    fun getSnapshot(): Snapshot = currentSnapshot

    fun start(port: Int = Constants.DEFAULT_TRANSPORT_PORT): Boolean {
        if (!running.compareAndSet(false, true)) {
            return true
        }
        update("starting", "Starting wireless Android Auto")
        val nativeStarted = AasdkNative.nativeStartAaOverTcp(port)
        if (!nativeStarted) {
            update("failed", "TCP listener failed: ${AasdkNative.nativeGetLastError()}")
            running.set(false)
            return false
        }
        update("tcp_listening", "AA TCP listener ready on port $port; starting hotspot")
        startHotspotThenBluetooth(port)
        return true
    }

    fun stop() {
        running.set(false)
        closeBluetooth()
        stopHotspot()
        AasdkNative.nativeStopAaSession()
        update("stopped", "Wireless Android Auto stopped")
    }

    private fun startHotspotThenBluetooth(port: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                        hotspotReservation = reservation
                        val config = reservation.wifiConfiguration
                        val ssid = config?.SSID?.trim('"').orEmpty()
                        val password = config?.preSharedKey?.trim('"').orEmpty()
                        val bssid = findWifiMacAddress()
                        update(
                            "hotspot",
                            "Hotspot ready: $ssid / $password, IP ${bestLocalIpAddress()}:$port; waiting for Bluetooth bootstrap"
                        )
                        startBluetoothServer(
                            ssid = ssid,
                            password = password,
                            bssid = bssid,
                            port = port,
                            dynamicAp = true
                        )
                    }

                    override fun onFailed(reason: Int) {
                        update(
                            "manual_hotspot_required",
                            "LocalOnlyHotspot failed ($reason). Enable a 2.4 GHz hotspot manually, then retry."
                        )
                        running.set(false)
                    }
                }, mainHandler)
            } catch (ex: Exception) {
                LogFileHelper.appendException(appContext, "Wireless hotspot start failed", ex)
                update("manual_hotspot_required", "Hotspot start failed: ${ex.message}")
                running.set(false)
            }
        } else {
            update(
                "manual_hotspot_required",
                "This Android version needs manual hotspot setup before wireless AA."
            )
            running.set(false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothServer(
        ssid: String,
        password: String,
        bssid: String,
        port: Int,
        dynamicAp: Boolean
    ) {
        if (!hasBluetoothPermission()) {
            update("failed", "Bluetooth permission missing")
            running.set(false)
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            update("failed", "Bluetooth is off")
            running.set(false)
            return
        }
        for (endpoint in AA_WIRELESS_ENDPOINTS) {
            val worker = Thread({
                listenForBluetoothClient(adapter, endpoint, ssid, password, bssid, port, dynamicAp)
            }, "LibAuto-WirelessBootstrap-${endpoint.label}")
            workers += worker
            worker.start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun listenForBluetoothClient(
        adapter: BluetoothAdapter,
        endpoint: RfcommEndpoint,
        ssid: String,
        password: String,
        bssid: String,
        port: Int,
        dynamicAp: Boolean
    ) {
        try {
            val socket = if (endpoint.secure) {
                adapter.listenUsingRfcommWithServiceRecord(
                    "LibAuto Wireless Android Auto",
                    endpoint.uuid
                )
            } else {
                adapter.listenUsingInsecureRfcommWithServiceRecord(
                    "LibAuto Wireless Android Auto",
                    endpoint.uuid
                )
            }
            synchronized(serverSockets) {
                serverSockets += socket
            }
            update(
                "bluetooth_listening",
                "Bluetooth bootstrap listening on ${endpoint.label}; pair/connect from Android Auto"
            )
            val client = socket.accept() ?: return
            clientSocket = client
            closeServerSocketsExcept(socket)
            update(
                "bluetooth_connected",
                "Bluetooth bootstrap connected on ${endpoint.label}: ${client.remoteDevice?.name ?: "phone"}"
            )
            handleRfcomm(client.inputStream, client.outputStream, ssid, password, bssid, port, dynamicAp)
        } catch (ex: Exception) {
            if (running.get()) {
                LogFileHelper.appendException(appContext, "Wireless Bluetooth bootstrap failed (${endpoint.label})", ex)
                logger("Wireless Bluetooth bootstrap failed on ${endpoint.label}: ${ex.message}")
            }
        }
    }

    private fun handleRfcomm(
        input: InputStream,
        output: OutputStream,
        ssid: String,
        password: String,
        bssid: String,
        port: Int,
        dynamicAp: Boolean
    ) {
        sendFrame(output, AawBootstrapProtocol.MESSAGE_WIFI_VERSION_REQUEST)
        sendFrame(
            output,
            AawBootstrapProtocol.MESSAGE_WIFI_START_REQUEST,
            AawBootstrapProtocol.wifiStartRequest(bestLocalIpAddress(), port)
        )
        val buffer = mutableListOf<Byte>()
        val chunk = ByteArray(1024)
        while (running.get()) {
            val read = input.read(chunk)
            if (read < 0) {
                break
            }
            for (index in 0 until read) {
                buffer += chunk[index]
            }
            for (frame in AawBootstrapProtocol.decodeFrames(buffer)) {
                when (frame.messageId) {
                    AawBootstrapProtocol.MESSAGE_WIFI_INFO_REQUEST -> {
                        update("wifi_info_requested", "Phone requested Wi-Fi credentials for $ssid")
                        sendFrame(
                            output,
                            AawBootstrapProtocol.MESSAGE_WIFI_INFO_RESPONSE,
                            AawBootstrapProtocol.wifiInfoResponse(ssid, password, bssid, dynamicAp)
                        )
                    }
                    AawBootstrapProtocol.MESSAGE_WIFI_VERSION_RESPONSE -> {
                        logger("Wireless version response: ${AawBootstrapProtocol.wifiVersionSummary(frame.payload)}")
                    }
                    AawBootstrapProtocol.MESSAGE_WIFI_START_RESPONSE -> {
                        val status = AawBootstrapProtocol.wifiStartResponseStatus(frame.payload)
                        update("wifi_start_response", "Phone Wi-Fi start response: $status")
                    }
                    AawBootstrapProtocol.MESSAGE_WIFI_CONNECTION_STATUS -> {
                        val status = AawBootstrapProtocol.wifiConnectionStatus(frame.payload)
                        update("wifi_connection_status", "Phone Wi-Fi connection status: $status")
                    }
                    else -> logger("Wireless ignored message id=${frame.messageId} bytes=${frame.payload.size}")
                }
            }
        }
    }

    private fun sendFrame(output: OutputStream, messageId: Int, payload: ByteArray = ByteArray(0)) {
        val frame = AawBootstrapProtocol.encodeFrame(messageId, payload)
        output.write(frame)
        output.flush()
        logger("Wireless sent message id=$messageId bytes=${payload.size}")
    }

    private fun closeBluetooth() {
        try {
            clientSocket?.close()
        } catch (_: Exception) {
        }
        try {
            synchronized(serverSockets) {
                serverSockets.forEach { it.close() }
                serverSockets.clear()
            }
        } catch (_: Exception) {
        }
        clientSocket = null
        workers.removeAll { !it.isAlive }
    }

    private fun closeServerSocketsExcept(keep: BluetoothServerSocket) {
        synchronized(serverSockets) {
            serverSockets.filter { it !== keep }.forEach {
                try {
                    it.close()
                } catch (_: Exception) {
                }
            }
            serverSockets.clear()
            serverSockets += keep
        }
    }

    private fun stopHotspot() {
        try {
            hotspotReservation?.close()
        } catch (_: Exception) {
        }
        hotspotReservation = null
    }

    private fun update(status: String, details: String) {
        currentSnapshot = Snapshot(status, details)
        logger("Wireless $status: $details")
        appContext.sendBroadcast(IntentBuilder.wirelessStatus(status, details))
    }

    private fun hasBluetoothPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH) ==
                PackageManager.PERMISSION_GRANTED
        }
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun bestLocalIpAddress(): String {
        return NetworkInfoProvider.getDeviceIpv4Addresses().firstOrNull {
            it.startsWith("192.168.") || it.startsWith("172.") || it.startsWith("10.")
        } ?: "192.168.43.1"
    }

    private fun findWifiMacAddress(): String {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .firstOrNull { it.name.startsWith("wlan", ignoreCase = true) && it.hardwareAddress != null }
                ?.hardwareAddress
                ?.joinToString(":") { "%02X".format(Locale.US, it) }
                ?: "02:00:00:00:00:00"
        } catch (_: Exception) {
            "02:00:00:00:00:00"
        }
    }

    private object IntentBuilder {
        fun wirelessStatus(status: String, details: String): android.content.Intent {
            return android.content.Intent(Constants.ACTION_WIRELESS_STATUS).apply {
                putExtra(Constants.EXTRA_STATUS, status)
                putExtra(Constants.EXTRA_WIRELESS_DETAILS, details)
            }
        }
    }

    private companion object {
        data class RfcommEndpoint(val uuid: UUID, val label: String, val secure: Boolean)

        /*
            OpenAuto registers 4de17a00-52cb-11e6-bdf4-0800200c9a66, while newer
            notes often mention 4de48490-8ab7-4fd6-970a-0ae4142618e3. Listening
            on both tells us which path the phone actually tries.
         */
        val AA_WIRELESS_ENDPOINTS: List<RfcommEndpoint> = listOf(
            RfcommEndpoint(UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66"), "openauto-secure", true),
            RfcommEndpoint(UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66"), "openauto-insecure", false),
            RfcommEndpoint(UUID.fromString("4de48490-8ab7-4fd6-970a-0ae4142618e3"), "aa-wireless-secure", true),
            RfcommEndpoint(UUID.fromString("4de48490-8ab7-4fd6-970a-0ae4142618e3"), "aa-wireless-insecure", false),
            RfcommEndpoint(UUID.fromString("669a0c20-0008-f4bd-e611-cb52007ae14d"), "openauto-reversed-secure", true),
            RfcommEndpoint(UUID.fromString("669a0c20-0008-f4bd-e611-cb52007ae14d"), "openauto-reversed-insecure", false)
        )
    }
}
