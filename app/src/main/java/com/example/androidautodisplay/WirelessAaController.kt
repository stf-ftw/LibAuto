package com.example.androidautodisplay

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
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
    private val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private val serverSockets = mutableListOf<BluetoothServerSocket>()
    private val bleAdvertiseCallbacks = mutableListOf<AdvertiseCallback>()
    private var gattServer: BluetoothGattServer? = null
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
        stopBleAdvertising()
        stopBleGattServer()
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
                        startBleAdvertising()
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
        startBleGattServer()
        startBleAdvertising(adapter)
        for (endpoint in AA_WIRELESS_ENDPOINTS) {
            val worker = Thread({
                listenForBluetoothClient(adapter, endpoint, ssid, password, bssid, port, dynamicAp)
            }, "LibAuto-WirelessBootstrap-${endpoint.label}")
            workers += worker
            worker.start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleAdvertising(adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()) {
        if (!hasBleAdvertisePermission()) {
            logger("Wireless BLE advertise skipped: permission missing")
            return
        }
        if (adapter == null || !adapter.isEnabled) {
            logger("Wireless BLE advertise skipped: Bluetooth off")
            return
        }
        if (!adapter.isMultipleAdvertisementSupported) {
            logger("Wireless BLE advertise skipped: controller does not support multiple advertisements")
            return
        }
        val advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            logger("Wireless BLE advertise skipped: advertiser unavailable")
            return
        }
        if (bleAdvertiseCallbacks.isNotEmpty()) {
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        for (endpoint in AA_BLE_ENDPOINTS) {
            val callback = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                    update(
                        "ble_advertising",
                        "BLE discovery advertising ${endpoint.label}; RFCOMM listeners are also active"
                    )
                }

                override fun onStartFailure(errorCode: Int) {
                    logger("Wireless BLE advertise failed ${endpoint.label}: $errorCode")
                }
            }
            try {
                advertiser.startAdvertising(
                    settings,
                    AdvertiseData.Builder()
                        .setIncludeDeviceName(false)
                        .addServiceUuid(ParcelUuid(endpoint.uuid))
                        .build(),
                    callback
                )
                bleAdvertiseCallbacks += callback
            } catch (ex: Exception) {
                LogFileHelper.appendException(appContext, "Wireless BLE advertise failed (${endpoint.label})", ex)
                logger("Wireless BLE advertise exception ${endpoint.label}: ${ex.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleGattServer() {
        if (!hasBluetoothPermission()) {
            logger("Wireless BLE GATT skipped: Bluetooth permission missing")
            return
        }
        if (gattServer != null) {
            return
        }
        try {
            val server = bluetoothManager.openGattServer(appContext, object : BluetoothGattServerCallback() {
                override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
                    val name = try {
                        device?.name
                    } catch (_: Exception) {
                        null
                    } ?: device?.address ?: "phone"
                    val state = if (newState == BluetoothProfile.STATE_CONNECTED) "connected" else "disconnected"
                    update("ble_gatt_$state", "BLE GATT $state: $name status=$status")
                }

                override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
                    logger("Wireless BLE GATT service added ${service?.uuid} status=$status")
                }

                override fun onCharacteristicReadRequest(
                    device: BluetoothDevice?,
                    requestId: Int,
                    offset: Int,
                    characteristic: BluetoothGattCharacteristic?
                ) {
                    logger("Wireless BLE GATT read ${characteristic?.uuid} offset=$offset")
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, ByteArray(0))
                }

                override fun onCharacteristicWriteRequest(
                    device: BluetoothDevice?,
                    requestId: Int,
                    characteristic: BluetoothGattCharacteristic?,
                    preparedWrite: Boolean,
                    responseNeeded: Boolean,
                    offset: Int,
                    value: ByteArray?
                ) {
                    logger(
                        "Wireless BLE GATT write ${characteristic?.uuid} bytes=${value?.size ?: 0} " +
                            "prepared=$preparedWrite response=$responseNeeded offset=$offset"
                    )
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, ByteArray(0))
                    }
                }
            }) ?: run {
                logger("Wireless BLE GATT unavailable")
                return
            }
            gattServer = server
            for (endpoint in AA_BLE_ENDPOINTS) {
                val service = BluetoothGattService(endpoint.uuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
                service.addCharacteristic(
                    BluetoothGattCharacteristic(
                        endpoint.characteristicUuid,
                        BluetoothGattCharacteristic.PROPERTY_READ or
                            BluetoothGattCharacteristic.PROPERTY_WRITE or
                            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                        BluetoothGattCharacteristic.PERMISSION_READ or
                            BluetoothGattCharacteristic.PERMISSION_WRITE
                    )
                )
                server.addService(service)
            }
            logger("Wireless BLE GATT server started")
        } catch (ex: Exception) {
            LogFileHelper.appendException(appContext, "Wireless BLE GATT start failed", ex)
            logger("Wireless BLE GATT start failed: ${ex.message}")
            stopBleGattServer()
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
                    RFCOMM_SERVICE_NAME,
                    endpoint.uuid
                )
            } else {
                adapter.listenUsingInsecureRfcommWithServiceRecord(
                    RFCOMM_SERVICE_NAME,
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

    @SuppressLint("MissingPermission")
    private fun stopBleAdvertising() {
        val advertiser: BluetoothLeAdvertiser = try {
            BluetoothAdapter.getDefaultAdapter()?.bluetoothLeAdvertiser ?: return
        } catch (_: Exception) {
            return
        }
        for (callback in bleAdvertiseCallbacks) {
            try {
                advertiser.stopAdvertising(callback)
            } catch (_: Exception) {
            }
        }
        bleAdvertiseCallbacks.clear()
    }

    @SuppressLint("MissingPermission")
    private fun stopBleGattServer() {
        try {
            gattServer?.close()
        } catch (_: Exception) {
        }
        gattServer = null
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

    private fun hasBleAdvertisePermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_ADMIN) ==
                PackageManager.PERMISSION_GRANTED
        }
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_ADVERTISE) ==
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
        data class RfcommEndpoint(
            val uuid: UUID,
            val label: String,
            val secure: Boolean,
            val characteristicUuid: UUID = UUID.fromString("4de17a01-52cb-11e6-bdf4-0800200c9a66")
        )

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
        val AA_BLE_ENDPOINTS: List<RfcommEndpoint> = listOf(
            RfcommEndpoint(
                UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66"),
                "openauto-ble",
                false,
                UUID.fromString("4de17a01-52cb-11e6-bdf4-0800200c9a66")
            )
        )
        const val RFCOMM_SERVICE_NAME = "OpenAuto Bluetooth Service"
    }
}
