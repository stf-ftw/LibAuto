package com.example.androidautodisplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class ProjectionService : Service() {
    private lateinit var sessionController: SessionController
    private lateinit var transportController: TransportTestController
    private lateinit var usbController: UsbIoController
    private lateinit var wirelessController: WirelessAaController
    private val logBuffer = ArrayDeque<String>()
    private val usbLogBuffer = ArrayDeque<String>()
    private var projectionRunning = false
    private var transportRunning = false
    private var usbMonitoring = false
    private var transportPort = Constants.DEFAULT_TRANSPORT_PORT
    private var lastConnections = 0L
    private var lastBytesIn = 0L
    private var lastBytesOut = 0L
    private var aaStarted = false
    private var aaStartInProgress = false
    private val nativeStopInProgress = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: "(null)"
            val extrasDump = intent.extras?.keySet()?.joinToString(", ") { key ->
                "$key=${intent.extras?.get(key)}"
            } ?: "(no extras)"
            appendUsbLog("USB_PERMISSION receiver fired; intentAction=$action extras=$extrasDump")
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(
                    android.hardware.usb.UsbManager.EXTRA_DEVICE,
                    android.hardware.usb.UsbDevice::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<android.hardware.usb.UsbDevice>(
                    android.hardware.usb.UsbManager.EXTRA_DEVICE
                )
            }
            val serviceIntent = Intent(this@ProjectionService, ProjectionService::class.java).apply {
                this.action = Constants.ACTION_USB_EVENT
                putExtra(Constants.EXTRA_USB_EVENT, action)
                if (device != null) {
                    putExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE, device)
                }
                if (Constants.USB_PERMISSION == action) {
                    val granted = intent.getBooleanExtra(
                        android.hardware.usb.UsbManager.EXTRA_PERMISSION_GRANTED,
                        false
                    )
                    putExtra(Constants.EXTRA_USB_PERMISSION_GRANTED, granted)
                }
            }
            handleUsbEvent(serviceIntent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        LogFileHelper.appendEvent(this, "ProjectionService", "onCreate")
        CarSensorBridge.initialize(this)
        sessionController = SessionController(this)
        transportController = TransportTestController(
            listener = object : TransportTestController.TransportTestListener {
                override fun onStatusUpdate(status: String) {
                    broadcastTransportStatus(status)
                }

                override fun onCountersUpdate(connections: Long, bytesIn: Long, bytesOut: Long) {
                    lastConnections = connections
                    lastBytesIn = bytesIn
                    lastBytesOut = bytesOut
                    broadcastTransportStatus("listening", connections, bytesIn, bytesOut)
                }
            },
            logger = object : TransportTestController.TransportTestLogger {
                override fun log(message: String) {
                    appendLog(message)
                }
            }
        )
        usbController = UsbIoController(
            context = this,
            logger = { appendUsbLog(it) },
            statusListener = { snapshot ->
                broadcastUsbStatus(snapshot)
            },
            aaReadyListener = {
                startAaSessionIfReady("auto")
            },
            sessionClosedListener = {
                mainHandler.post {
                    synchronized(this@ProjectionService) {
                        aaStarted = false
                        aaStartInProgress = false
                    }
                    CarSensorBridge.stop()
                    appendUsbLog("AASDK session reset after USB close")
                }
                stopAaSessionAsync("usb-close")
            },
            sessionStalledListener = {
                mainHandler.post {
                    synchronized(this@ProjectionService) {
                        aaStarted = false
                        aaStartInProgress = false
                    }
                    CarSensorBridge.stop()
                    appendUsbLog("AASDK session reset after USB transport stall")
                }
                stopAaSessionAsync("usb-stall")
            }
        )
        wirelessController = WirelessAaController(this) { message ->
            appendUsbLog(message)
            LogFileHelper.appendEvent(this, "WirelessAa", message)
        }
        UsbJniBridge.attach(usbController)
        val initOk = AasdkNative.nativeInit()
        AasdkNative.nativeSetLogPath(LogFileHelper.getNativeLogFile(this).absolutePath)
        applyProjectionResolutionSetting()
        appendUsbLog("AASDK native init result: $initOk")
        appendUsbLog("Native file logging: ${LogFileHelper.getNativeLogFile(this).absolutePath}")
        registerReceiver(
            usbPermissionReceiver,
            IntentFilter(Constants.USB_PERMISSION)
        )
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        LogFileHelper.appendEvent(this, "ProjectionService", "onStartCommand action=$action")
        when (action) {
            Constants.ACTION_START -> {
                projectionRunning = true
                ensureForeground("Projection running")
                val mode = intent.getStringExtra("mode")
                if (mode == "wifi") {
                    sessionController.startWifi()
                } else {
                    val manager = UsbConnectionManager(this)
                    val device = manager.listDevices().firstOrNull { manager.hasPermission(it) }
                    if (device != null) {
                        sessionController.startUsb(device)
                    }
                }
            }
            Constants.ACTION_STOP -> {
                sessionController.stop()
                wirelessController.stop()
                stopAaSessionAsync("service-stop")
                projectionRunning = false
                maybeStopService()
            }
            Constants.ACTION_WIRELESS_START -> {
                projectionRunning = true
                ensureForeground("Wireless Android Auto")
                appendUsbLog("Wireless AA start requested")
                CarSensorBridge.start()
                applyProjectionResolutionSetting()
                val ok = wirelessController.start(Constants.DEFAULT_WIRELESS_AA_PORT)
                appendUsbLog("Wireless AA start result: $ok")
            }
            Constants.ACTION_WIRELESS_STOP -> {
                appendUsbLog("Wireless AA stop requested")
                wirelessController.stop()
                stopAaSessionAsync("wireless-stop")
                CarSensorBridge.stop()
                projectionRunning = false
                maybeStopService()
            }
            Constants.ACTION_TRANSPORT_START -> {
                transportPort = intent.getIntExtra(Constants.EXTRA_PORT, Constants.DEFAULT_TRANSPORT_PORT)
                transportRunning = true
                ensureForeground("Transport test running")
                appendLog("Transport start requested on port $transportPort")
                lastConnections = 0
                lastBytesIn = 0
                lastBytesOut = 0
                transportController.start(transportPort)
                broadcastTransportStatus("listening", lastConnections, lastBytesIn, lastBytesOut)
            }
            Constants.ACTION_TRANSPORT_STOP -> {
                appendLog("Transport stop requested")
                transportController.stop()
                transportRunning = false
                broadcastTransportStatus("stopped", lastConnections, lastBytesIn, lastBytesOut)
                maybeStopService()
            }
            Constants.ACTION_TRANSPORT_SEND -> {
                val host = intent.getStringExtra(Constants.EXTRA_HOST) ?: return START_STICKY
                val port = intent.getIntExtra(Constants.EXTRA_PORT, Constants.DEFAULT_TRANSPORT_PORT)
                appendLog("Send test packet to $host:$port")
                transportController.sendTestPacket(host, port)
            }
            Constants.ACTION_TRANSPORT_PING -> {
                val host = intent.getStringExtra(Constants.EXTRA_HOST) ?: return START_STICKY
                val port = intent.getIntExtra(Constants.EXTRA_PORT, Constants.DEFAULT_TRANSPORT_PORT)
                appendLog("Ping/connect test to $host:$port")
                transportController.ping(host, port) { success, latency ->
                    val resultIntent = Intent(Constants.ACTION_TRANSPORT_PING)
                    resultIntent.putExtra(Constants.EXTRA_SUCCESS, success)
                    resultIntent.putExtra(Constants.EXTRA_LATENCY_MS, latency)
                    sendBroadcast(resultIntent)
                }
            }
            Constants.ACTION_TRANSPORT_SAVE_LOGS -> {
                val path = saveLogsToFile()
                val resultIntent = Intent(Constants.ACTION_TRANSPORT_LOGS)
                resultIntent.putExtra(Constants.EXTRA_LOG_PATH, path)
                sendBroadcast(resultIntent)
            }
            Constants.ACTION_USB_MONITOR_START -> {
                usbMonitoring = true
                ensureForeground("USB monitoring")
                appendUsbLog("USB monitoring started")
                usbController.startMonitoring()
            }
            Constants.ACTION_USB_MONITOR_STOP -> {
                appendUsbLog("USB monitoring stopped")
                usbController.stopMonitoring()
                usbMonitoring = false
                maybeStopService()
            }
            Constants.ACTION_USB_SEND_TEST -> {
                appendUsbLog("USB test send requested")
                usbController.sendTestPacket()
            }
            Constants.ACTION_USB_ENABLE_AOAP -> {
                ensureForeground("AOAP requested")
                appendUsbLog("AOAP enable requested")
                val ok = usbController.enableAccessoryMode()
                appendUsbLog("AOAP enable result: $ok")
            }
            Constants.ACTION_USB_AA_START -> {
                ensureForeground("AA session starting")
                appendUsbLog("Start AA pressed")
                if (usbController.requestAaStart()) {
                    startAaSessionIfReady("manual")
                } else {
                    appendUsbLog("AA start blocked: already started")
                }
            }
            Constants.ACTION_USB_SELECT_AND_START -> {
                usbMonitoring = true
                ensureForeground("USB device selected")
                LogFileHelper.appendEvent(this, "ProjectionService", "select/start: startMonitoring")
                usbController.startMonitoring()
                val deviceName = intent.getStringExtra(Constants.EXTRA_USB_DEVICE_NAME)
                appendUsbLog("USB select/start requested device=$deviceName")
                LogFileHelper.appendEvent(this, "ProjectionService", "select/start device=$deviceName")
                if (deviceName.isNullOrBlank()) {
                    appendUsbLog("USB select/start blocked: no device name")
                } else if (!usbController.selectDeviceForAa(deviceName)) {
                    appendUsbLog("USB select/start failed for $deviceName")
                }
            }
            Constants.ACTION_USB_EXPORT_LOGS -> {
                val path = saveUsbLogsToFile(includeLogcat = false)
                val uri = path?.let { getUsbLogUri(it) }
                val resultIntent = Intent(Constants.ACTION_USB_LOGS)
                resultIntent.putExtra(Constants.EXTRA_USB_LOG_PATH, path)
                resultIntent.putExtra(Constants.EXTRA_USB_LOG_URI, uri?.toString())
                sendBroadcast(resultIntent)
            }
            Constants.ACTION_USB_EXPORT_LOGCAT -> {
                val path = saveUsbLogsToFile(includeLogcat = true)
                val uri = path?.let { getUsbLogUri(it) }
                val resultIntent = Intent(Constants.ACTION_USB_LOGS)
                resultIntent.putExtra(Constants.EXTRA_USB_LOG_PATH, path)
                resultIntent.putExtra(Constants.EXTRA_USB_LOG_URI, uri?.toString())
                sendBroadcast(resultIntent)
            }
            Constants.ACTION_USB_SHARE_LOGS -> {
                val path = saveUsbLogsToFile(includeLogcat = false)
                val uri = path?.let { getUsbLogUri(it) }
                val resultIntent = Intent(Constants.ACTION_USB_LOGS)
                resultIntent.putExtra(Constants.EXTRA_USB_LOG_PATH, path)
                resultIntent.putExtra(Constants.EXTRA_USB_LOG_URI, uri?.toString())
                resultIntent.putExtra(Constants.EXTRA_USB_SHARE, true)
                sendBroadcast(resultIntent)
            }
            Constants.ACTION_USB_PROBE -> {
                appendUsbLog("USB probe requested")
                Thread {
                    val result = usbController.probeEndpoints()
                    appendUsbLog(result)
                    broadcastUsbStatus(usbController.getSnapshot())
                }.start()
            }
            Constants.ACTION_USB_EVENT -> {
                usbMonitoring = true
                ensureForeground("USB monitoring")
                handleUsbEvent(intent)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("LibAuto")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            Constants.NOTIFICATION_CHANNEL_ID,
            "Projection",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun ensureForeground(text: String) {
        startForeground(Constants.NOTIFICATION_ID, buildNotification(text))
    }

    private fun broadcastTransportStatus(
        status: String,
        connections: Long = 0,
        bytesIn: Long = 0,
        bytesOut: Long = 0
    ) {
        val intent = Intent(Constants.ACTION_TRANSPORT_STATUS)
        intent.putExtra(Constants.EXTRA_STATUS, status)
        intent.putExtra(Constants.EXTRA_CONNECTIONS, connections)
        intent.putExtra(Constants.EXTRA_BYTES_IN, bytesIn)
        intent.putExtra(Constants.EXTRA_BYTES_OUT, bytesOut)
        sendBroadcast(intent)
    }

    private fun broadcastUsbStatus(snapshot: UsbIoController.UsbStatusSnapshot) {
        val intent = Intent(Constants.ACTION_USB_STATUS)
        intent.putExtra(Constants.EXTRA_USB_DEVICES, snapshot.devicesSummary)
        intent.putExtra(Constants.EXTRA_USB_PERMISSION, snapshot.permissionSummary)
        intent.putExtra(Constants.EXTRA_USB_SELECTION, snapshot.selectionSummary)
        intent.putExtra(Constants.EXTRA_USB_ENDPOINTS, snapshot.endpointsSummary)
        intent.putExtra(Constants.EXTRA_USB_ACCESSORY, snapshot.accessorySummary)
        intent.putExtra(Constants.EXTRA_USB_INTERFACE_COUNT, snapshot.interfaceCount)
        intent.putExtra(Constants.EXTRA_USB_BULK_IN_COUNT, snapshot.bulkInCount)
        intent.putExtra(Constants.EXTRA_USB_BULK_OUT_COUNT, snapshot.bulkOutCount)
        intent.putExtra(Constants.EXTRA_USB_READY, snapshot.readyForIo)
        intent.putExtra(Constants.EXTRA_USB_PROBE_RESULTS, snapshot.probeSummary)
        intent.putExtra(Constants.EXTRA_USB_STATE, snapshot.stateSummary)
        intent.putExtra(Constants.EXTRA_USB_BYTES_IN, snapshot.bytesIn)
        intent.putExtra(Constants.EXTRA_USB_BYTES_OUT, snapshot.bytesOut)
        intent.putExtra(Constants.EXTRA_USB_LAST_ERROR, snapshot.lastError)
        sendBroadcast(intent)
    }

    private fun appendLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val entry = "$timestamp $message"
        synchronized(logBuffer) {
            logBuffer.add(entry)
            while (logBuffer.size > 120) {
                logBuffer.removeFirst()
            }
        }
    }

    private fun appendUsbLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val entry = "$timestamp $message"
        synchronized(usbLogBuffer) {
            usbLogBuffer.add(entry)
            while (usbLogBuffer.size > 160) {
                usbLogBuffer.removeFirst()
            }
        }
    }

    private fun saveLogsToFile(): String? {
        val dir = File(filesDir, "transport_logs")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "transport-log-$timestamp.txt")
        return try {
            val snapshot = synchronized(logBuffer) { logBuffer.toList() }
            file.writeText(snapshot.joinToString("\n"))
            file.absolutePath
        } catch (ex: Exception) {
            appendLog("Failed to save logs: ${ex.message}")
            null
        }
    }

    private fun saveUsbLogsToFile(includeLogcat: Boolean): String? {
        val dir = getUsbLogDir()
        if (!dir.exists() && !dir.mkdirs()) {
            appendUsbLog("Failed to create USB log directory: ${dir.absolutePath}")
            return null
        }
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "usb-log-$timestamp.txt")
        return try {
            val snapshot = synchronized(usbLogBuffer) { usbLogBuffer.toList() }
            val summary = usbController.getDeviceSummary()
            val builder = StringBuilder()
            builder.append("=== USB Descriptor Summary ===\n")
            builder.append(summary)
            builder.append("\n\n=== Kotlin USB Log ===\n")
            builder.append(snapshot.joinToString("\n"))
            builder.append("\n\n=== AOAP Identity Checklist ===\n")
            builder.append("Capture from working head unit:\n")
            builder.append(" - manufacturer\n - model\n - description\n - version\n - uri\n - serial\n")
            builder.append("Current AOAP identity:\n")
            builder.append(usbController.getAoapIdentitySummary().joinToString("\n"))
            builder.append("\n\n=== AOAP Log ===\n")
            val aoapLines = usbController.getAoapLog()
            if (aoapLines.isEmpty()) {
                builder.append("(no AOAP entries)")
            } else {
                builder.append(aoapLines.joinToString("\n"))
            }
            val nativeLog = LogFileHelper.getNativeLogFile(this)
            if (nativeLog.exists()) {
                builder.append("\n\n=== Native File Log ===\n")
                builder.append(nativeLog.readText())
            } else {
                builder.append("\n\n=== Native File Log ===\n")
                builder.append("(native log file missing)")
            }
            val eventLog = LogFileHelper.getEventLogFile(this)
            if (eventLog.exists()) {
                builder.append("\n\n=== LibAuto Event Log ===\n")
                builder.append(eventLog.readText())
            } else {
                builder.append("\n\n=== LibAuto Event Log ===\n")
                builder.append("(event log missing)")
            }
            if (includeLogcat) {
                builder.append("\n\n=== Native Logcat ===\n")
                builder.append(captureLogcat())
            }
            file.writeText(builder.toString())
            file.absolutePath
        } catch (ex: Exception) {
            appendUsbLog("Failed to save USB logs: ${ex.message}")
            null
        }
    }

    private fun captureLogcat(): String {
        return try {
            val command = listOf(
                "logcat",
                "-d",
                "-v",
                "time",
                "AndroidRuntime:E",
                "*:W",
                "AASDKJNI:I",
                "AndroidUsbTransport:I",
                "UsbTransport:I",
                "*:S"
            )
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            buildString {
                append("command: ")
                append(command.joinToString(" "))
                append("\nexitCode: ")
                append(exitCode)
                append("\n\n")
                append(output.trim().ifEmpty { "(no logcat output)" })
            }
        } catch (ex: Exception) {
            "Failed to capture logcat: ${ex.message}"
        }
    }

    private fun getUsbLogDir(): File {
        return LogFileHelper.getLogDir(this)
    }

    private fun getUsbLogUri(path: String): Uri? {
        return try {
            FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                File(path)
            )
        } catch (ex: Exception) {
            appendUsbLog("Failed to create log share URI: ${ex.message}")
            null
        }
    }

    private fun startAaSessionIfReady(source: String) {
        LogFileHelper.appendEvent(this, "ProjectionService", "startAaSessionIfReady source=$source")
        synchronized(this) {
            if (aaStarted || aaStartInProgress) {
                appendUsbLog("AA start ignored ($source): already started or starting")
                return
            }
            aaStartInProgress = true
        }
        appendUsbLog("Starting AASDK / AA session ($source)")
        val ready = usbController.getSnapshot().readyForIo
        appendUsbLog("AA start requested; usbReady=$ready")
        LogFileHelper.appendEvent(this, "ProjectionService", "AA start requested usbReady=$ready")
        if (!ready) {
            appendUsbLog("AA start blocked: USB not ready")
            synchronized(this) {
                aaStartInProgress = false
            }
            return
        }
        usbController.markAaStarting()
        CarSensorBridge.start()
        applyProjectionResolutionSetting()
        val ok = AasdkNative.nativeStartAaOverUsb()
        appendUsbLog("AA native start result: $ok")
        LogFileHelper.appendEvent(this, "ProjectionService", "AA native start result=$ok")
        if (ok) {
            appendUsbLog("AASDK session started")
            synchronized(this) {
                aaStarted = true
                aaStartInProgress = false
            }
            usbController.markAaStarted()
            usbController.markTlsHandshake()
        } else {
            synchronized(this) {
                aaStartInProgress = false
            }
            CarSensorBridge.stop()
        }
    }

    private fun applyProjectionResolutionSetting() {
        val prefs = getSharedPreferences(Constants.PROJECTION_PREFS, MODE_PRIVATE)
        val key = prefs.getString(Constants.PROJECTION_RESOLUTION, Constants.DEFAULT_PROJECTION_RESOLUTION)
        val useNativeAspect = prefs.getBoolean(Constants.PROJECTION_NATIVE_ASPECT, false)
        val fps = prefs.getInt(Constants.PROJECTION_FPS, Constants.DEFAULT_PROJECTION_FPS)
            .let { if (it == 30 || it == 60) it else Constants.DEFAULT_PROJECTION_FPS }
        val resolution = ProjectionResolutionOptions.resolve(this, key, useNativeAspect)
        AasdkNative.nativeSetVideoResolution(
            resolution.width,
            resolution.height,
            resolution.frameWidth,
            resolution.frameHeight,
            resolution.marginWidth,
            resolution.marginHeight,
            resolution.nativeCode
        )
        AasdkNative.nativeSetVideoFps(fps)
        appendUsbLog(
            "Projection resolution: active=${resolution.width}x${resolution.height} " +
                "frame=${resolution.frameWidth}x${resolution.frameHeight} " +
                "margins=${resolution.marginWidth}x${resolution.marginHeight} fps=$fps"
        )
    }

    private fun handleUsbEvent(intent: Intent) {
        val event = intent.getStringExtra(Constants.EXTRA_USB_EVENT)
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<android.hardware.usb.UsbDevice>(
                android.hardware.usb.UsbManager.EXTRA_DEVICE
            )
        }
        if (event == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED && device != null) {
            usbController.handleDeviceAttached(device)
        } else if (event == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED && device != null) {
            synchronized(this) {
                aaStarted = false
                aaStartInProgress = false
            }
            CarSensorBridge.stop()
            usbController.handleDeviceDetached(device)
            stopAaSessionAsync("usb-detached")
        } else if (event == Constants.USB_PERMISSION && device != null) {
            val granted = intent.getBooleanExtra(Constants.EXTRA_USB_PERMISSION_GRANTED, false)
            usbController.handlePermissionResult(device, granted)
        }
    }

    private fun stopAaSessionAsync(reason: String) {
        if (!nativeStopInProgress.compareAndSet(false, true)) {
            appendUsbLog("AASDK native stop already running ($reason)")
            return
        }
        Thread({
            try {
                LogFileHelper.appendEvent(this, "ProjectionService", "nativeStopAaSession begin reason=$reason")
                AasdkNative.nativeStopAaSession()
                LogFileHelper.appendEvent(this, "ProjectionService", "nativeStopAaSession end reason=$reason")
            } finally {
                nativeStopInProgress.set(false)
            }
        }, "libauto-aa-stop").apply {
            isDaemon = true
            start()
        }
    }

    private fun maybeStopService() {
        if (!projectionRunning && !transportRunning && !usbMonitoring) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        unregisterReceiver(usbPermissionReceiver)
        UsbJniBridge.detach()
        if (::wirelessController.isInitialized) {
            wirelessController.stop()
        }
        CarSensorBridge.stop()
        stopAaSessionAsync("service-destroy")
        usbController.stopMonitoring()
        super.onDestroy()
    }
}
