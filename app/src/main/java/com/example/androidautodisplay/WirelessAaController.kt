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
import java.io.File
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
    private val bootstrapConnected = AtomicBoolean(false)
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private val serverSockets = mutableListOf<BluetoothServerSocket>()
    private val bleAdvertiseCallbacks = mutableListOf<AdvertiseCallback>()
    private var gattServer: BluetoothGattServer? = null
    @Volatile private var bleAdvertiseBroken = false
    private var clientSocket: BluetoothSocket? = null
    private val workers = mutableListOf<Thread>()
    @Volatile private var currentSnapshot = Snapshot("stopped", "Wireless Android Auto stopped")

    fun getSnapshot(): Snapshot = currentSnapshot

    fun start(port: Int = Constants.DEFAULT_WIRELESS_AA_PORT): Boolean {
        if (!running.compareAndSet(false, true)) {
            return true
        }
        bootstrapConnected.set(false)
        bleAdvertiseBroken = false
        update("starting", "Starting wireless Android Auto (Bluetooth + Phone Hotspot)...")
        AasdkNative.nativeStopAaSession()
        Thread.sleep(200)

        val nativeStarted = AasdkNative.nativeStartAaOverTcp(port)
        if (!nativeStarted) {
            update("failed", "TCP listener failed: ${AasdkNative.nativeGetLastError()}")
            running.set(false)
            return false
        }
        val currentIp = NetworkInfoProvider.getWifiIpAddress() ?: bestLocalIpAddress()
        val currentSsid = WifiInfoProvider.getWifiSsid(appContext)
        val bssid = findWifiMacAddress()
        val isWifiActive = currentSsid != "not connected" && !currentSsid.contains("unknown")

        val statusMsg = if (isWifiActive) {
            "AA TCP listening on $currentIp:$port (Wi-Fi: $currentSsid); waiting for Bluetooth bootstrap"
        } else {
            "AA TCP listening on port $port; please connect stereo to phone hotspot; waiting for Bluetooth bootstrap"
        }
        update("tcp_listening", statusMsg)
        startBluetoothServer(
            ssid = if (isWifiActive) currentSsid else "",
            password = "",
            bssid = bssid,
            port = port,
            dynamicAp = false
        )
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
                        startBluetoothServer(
                            ssid = ssid,
                            password = password,
                            bssid = bssid,
                            port = port,
                            dynamicAp = true
                        )
                        startHotspotClientMonitor()
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
            update("failed", "Bluetooth permission missing. Please grant Bluetooth permissions.")
            running.set(false)
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            update("failed", "Bluetooth is off on stereo. Please turn Bluetooth on.")
            running.set(false)
            return
        }
        try {
            if (adapter.isMultipleAdvertisementSupported) {
                if (startBleGattServer()) {
                    startBleAdvertising(adapter)
                }
            } else {
                logger("Wireless BLE advertise skipped: controller does not support multiple advertisements")
            }
        } catch (ex: Exception) {
            logger("Wireless BLE setup skipped: ${ex.message}")
        }
        val worker = Thread({
            listenForBluetoothClient(adapter, ssid, password, bssid, port, dynamicAp)
        }, "LibAuto-WirelessBluetoothListener")
        workers += worker
        worker.start()
    }

    @SuppressLint("MissingPermission")
    private fun startBleAdvertising(adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()) {
        if (bleAdvertiseBroken) {
            logger("Wireless BLE advertise skipped: framework already failed this run")
            return
        }
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
                bleAdvertiseBroken = true
                logger("Wireless BLE advertise exception ${endpoint.label}: ${ex.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleGattServer(): Boolean {
        if (!hasBluetoothPermission()) {
            logger("Wireless BLE GATT skipped: Bluetooth permission missing")
            return false
        }
        if (gattServer != null) {
            return true
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
                return false
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
            return true
        } catch (ex: Exception) {
            LogFileHelper.appendException(appContext, "Wireless BLE GATT start failed", ex)
            logger("Wireless BLE GATT start failed: ${ex.message}")
            stopBleGattServer()
            return false
        }
    }

    @SuppressLint("MissingPermission")
    private fun listenForBluetoothClient(
        adapter: BluetoothAdapter,
        ssid: String,
        password: String,
        bssid: String,
        port: Int,
        dynamicAp: Boolean
    ) {
        val primaryUuid = UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66")
        val altUuid = UUID.fromString("4de48490-8ab7-4fd6-970a-0ae4142618e3")

        var serverSocket: BluetoothServerSocket? = null

        // Try secure RFCOMM first on standard AA UUID; if stereo HAL does not support secure, fallback to insecure
        try {
            serverSocket = adapter.listenUsingRfcommWithServiceRecord("AndroidAuto", primaryUuid)
            logger("Wireless RFCOMM secure listener registered on $primaryUuid")
        } catch (ex1: Exception) {
            logger("Wireless RFCOMM secure listen failed ($primaryUuid): ${ex1.message}, trying insecure...")
            try {
                serverSocket = adapter.listenUsingInsecureRfcommWithServiceRecord("AndroidAuto", primaryUuid)
                logger("Wireless RFCOMM insecure listener registered on $primaryUuid")
            } catch (ex2: Exception) {
                logger("Wireless RFCOMM insecure listen failed: ${ex2.message}, trying alt UUID...")
                try {
                    serverSocket = adapter.listenUsingInsecureRfcommWithServiceRecord("AndroidAuto", altUuid)
                    logger("Wireless RFCOMM insecure listener registered on alt $altUuid")
                } catch (ex3: Exception) {
                    logger("All RFCOMM server socket registrations failed: ${ex3.message}")
                    update(
                        "failed",
                        "Stereo Bluetooth RFCOMM not supported by car hardware (${ex3.message}). Please use Headunit Server method."
                    )
                    return
                }
            }
        }

        if (serverSocket == null) return

        synchronized(serverSockets) {
            serverSockets += serverSocket
        }

        val bondedNames = try {
            adapter.bondedDevices?.mapNotNull { it.name }?.joinToString(", ") ?: "none"
        } catch (_: Exception) {
            "none"
        }
        update(
            "bluetooth_listening",
            "Waiting for phone connection via Bluetooth... (Paired: $bondedNames)"
        )

        // If paired phones exist, give inbound 3.5 seconds, then attempt outbound connect to trigger phone AA
        val bondedPhones = try {
            adapter.bondedDevices?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        if (bondedPhones.isNotEmpty()) {
            val outboundWorker = Thread({
                try {
                    Thread.sleep(3500)
                    if (!running.get() || bootstrapConnected.get()) return@Thread
                    for (phone in bondedPhones) {
                        if (!running.get() || bootstrapConnected.get()) break
                        val remoteName = safeRemoteName(phone)
                        logger("Attempting outbound Bluetooth RFCOMM trigger to $remoteName...")
                        val socketFactories = listOf(
                            { phone.createRfcommSocketToServiceRecord(primaryUuid) },
                            { phone.createInsecureRfcommSocketToServiceRecord(primaryUuid) },
                            { phone.createInsecureRfcommSocketToServiceRecord(altUuid) }
                        )
                        for (factory in socketFactories) {
                            if (!running.get() || bootstrapConnected.get()) break
                            try {
                                val sock = factory()
                                sock.connect()
                                if (bootstrapConnected.compareAndSet(false, true)) {
                                    clientSocket = sock
                                    closeBluetoothServers()
                                    update(
                                        "bluetooth_connected",
                                        "Connected to $remoteName via Bluetooth! Launching projection..."
                                    )
                                    handleRfcomm(sock.inputStream, sock.outputStream, ssid, password, bssid, port, dynamicAp)
                                    break
                                } else {
                                    sock.close()
                                }
                            } catch (e: Exception) {
                                logger("Outbound RFCOMM attempt to $remoteName failed: ${e.message}")
                            }
                        }
                    }
                } catch (_: InterruptedException) {}
            }, "LibAuto-WirelessBluetoothOutbound")
            workers += outboundWorker
            outboundWorker.start()
        }

        try {
            while (running.get() && !bootstrapConnected.get()) {
                val client = try {
                    serverSocket.accept()
                } catch (e: Exception) {
                    if (running.get()) {
                        logger("RFCOMM accept closed: ${e.message}")
                    }
                    break
                } ?: break

                if (!bootstrapConnected.compareAndSet(false, true)) {
                    try { client.close() } catch (_: Exception) {}
                    break
                }

                clientSocket = client
                val remoteName = safeRemoteName(client.remoteDevice)
                update("bluetooth_connected", "Bluetooth connected: $remoteName! Launching projection...")

                handleRfcomm(client.inputStream, client.outputStream, ssid, password, bssid, port, dynamicAp)
                break
            }
        } finally {
            try { serverSocket.close() } catch (_: Exception) {}
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
        var resolvedIp = NetworkInfoProvider.getWifiIpAddress()
        if (resolvedIp.isNullOrBlank()) {
            update("waiting_wifi", "Bluetooth connected! Waiting for Wi-Fi connection...")
            for (i in 1..8) {
                try { Thread.sleep(500) } catch (_: InterruptedException) { break }
                resolvedIp = NetworkInfoProvider.getWifiIpAddress()
                if (!resolvedIp.isNullOrBlank()) break
            }
        }
        val currentIp = resolvedIp ?: bestLocalIpAddress()
        update(
            "bootstrap_sending",
            "Sending wireless bootstrap: ip=$currentIp port=$port"
        )
        sendFrame(output, AawBootstrapProtocol.MESSAGE_WIFI_VERSION_REQUEST)
        sendFrame(
            output,
            AawBootstrapProtocol.MESSAGE_WIFI_START_REQUEST,
            AawBootstrapProtocol.wifiStartRequest(currentIp, port)
        )
        val buffer = mutableListOf<Byte>()
        val chunk = ByteArray(1024)
        while (running.get()) {
            val read = input.read(chunk)
            if (read < 0) {
                logger("Wireless RFCOMM input closed")
                break
            }
            logger("Wireless received RFCOMM bytes=$read hex=${chunk.copyOf(read).toHex(MAX_HEX_LOG_BYTES)}")
            for (index in 0 until read) {
                buffer += chunk[index]
            }
            for (frame in AawBootstrapProtocol.decodeFrames(buffer)) {
                when (frame.messageId) {
                    AawBootstrapProtocol.MESSAGE_WIFI_INFO_REQUEST -> {
                        update("wifi_info_requested", "Phone requested Wi-Fi credentials")
                        sendFrame(
                            output,
                            AawBootstrapProtocol.MESSAGE_WIFI_INFO_RESPONSE,
                            AawBootstrapProtocol.wifiInfoResponse(ssid, password, bssid, dynamicAp)
                        )
                    }
                    AawBootstrapProtocol.MESSAGE_WIFI_VERSION_RESPONSE -> {
                        logger("Wireless version response: ${AawBootstrapProtocol.wifiVersionSummary(frame.payload)}")
                        sendFrame(
                            output,
                            AawBootstrapProtocol.MESSAGE_WIFI_START_REQUEST,
                            AawBootstrapProtocol.wifiStartRequest(currentIp, port)
                        )
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
        logger("Wireless sent message id=$messageId payloadBytes=${payload.size} frameHex=${frame.toHex(MAX_HEX_LOG_BYTES)}")
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

    private fun closeBluetoothServers() {
        try {
            synchronized(serverSockets) {
                serverSockets.forEach { it.close() }
                serverSockets.clear()
            }
        } catch (_: Exception) {
        }
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

    private fun startHotspotClientMonitor() {
        val worker = Thread({
            var last = ""
            while (running.get() && !bootstrapConnected.get()) {
                val snapshot = readArpSnapshot()
                if (snapshot.isNotBlank() && snapshot != last) {
                    last = snapshot
                    logger("Wireless hotspot neighbour snapshot: $snapshot")
                }
                try {
                    Thread.sleep(HOTSPOT_CLIENT_POLL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "LibAuto-WirelessHotspotClients")
        workers += worker
        worker.start()
    }

    private fun readArpSnapshot(): String {
        return try {
            File("/proc/net/arp").readLines()
                .drop(1)
                .mapNotNull { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 6 && parts[5].startsWith("wlan", ignoreCase = true)) {
                        "${parts[0]} ${parts[3]} ${parts[5]}"
                    } else {
                        null
                    }
                }
                .joinToString("; ")
        } catch (_: Exception) {
            ""
        }
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

    @SuppressLint("MissingPermission")
    private fun safeRemoteName(device: BluetoothDevice?): String {
        if (device == null) {
            return "phone"
        }
        val name = try {
            device.name
        } catch (_: Exception) {
            null
        }
        return "${name ?: "phone"} ${device.address ?: ""}".trim()
    }

    private fun ByteArray.toHex(maxBytes: Int): String {
        return take(maxBytes).joinToString("") { "%02x".format(Locale.US, it) } +
            if (size > maxBytes) "...(+${size - maxBytes})" else ""
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
        val OUTBOUND_WIRELESS_ENDPOINTS: List<RfcommEndpoint> = listOf(
            RfcommEndpoint(UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66"), "openauto-out-secure", true),
            RfcommEndpoint(UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66"), "openauto-out-insecure", false),
            RfcommEndpoint(UUID.fromString("4de48490-8ab7-4fd6-970a-0ae4142618e3"), "aa-wireless-out-secure", true),
            RfcommEndpoint(UUID.fromString("4de48490-8ab7-4fd6-970a-0ae4142618e3"), "aa-wireless-out-insecure", false)
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
        const val MAX_HEX_LOG_BYTES = 128
        const val HOTSPOT_CLIENT_POLL_MS = 2000L
    }
}
