package com.example.androidautodisplay

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class UsbIoController(
    private val context: Context,
    private val logger: (String) -> Unit,
    private val statusListener: (UsbStatusSnapshot) -> Unit,
    private val aaReadyListener: (() -> Unit)? = null,
    private val sessionClosedListener: (() -> Unit)? = null,
    private val sessionStalledListener: (() -> Unit)? = null
) {
    private enum class UsbState {
        IDLE,
        USB_ATTACHED,
        PRE_AA,
        AOAP_NEGOTIATING,
        WAITING_FOR_AOAP_REENUMERATION,
        READY_FOR_AA,
        STARTING_AA,
        AA_TLS_HANDSHAKE,
        AA_SESSION_ACTIVE,
        DISCONNECTED,
        ERROR
    }

    data class UsbStatusSnapshot(
        val devicesSummary: String,
        val permissionSummary: String,
        val selectionSummary: String,
        val endpointsSummary: String,
        val accessorySummary: String,
        val interfaceCount: Int,
        val bulkInCount: Int,
        val bulkOutCount: Int,
        val readyForIo: Boolean,
        val probeSummary: String,
        val stateSummary: String,
        val bytesIn: Long,
        val bytesOut: Long,
        val lastError: String?
    )

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val prefs = context.getSharedPreferences("usb_prefs", Context.MODE_PRIVATE)
    private val readRunning = AtomicBoolean(false)
    private val externalReadActive = AtomicBoolean(false)
    private var readThread: Thread? = null
    private val deviceCache = linkedMapOf<String, UsbDevice>()
    private var state = UsbState.IDLE
    private var lastSeenDeviceName: String? = null
    private var lastSeenVid = -1
    private var lastSeenPid = -1
    private var pendingPermissionDeviceName: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var retryScheduled = false
    private var writeTimeoutCount = 0

    private var selectedDevice: UsbDevice? = null
    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null
    private var selectedInterfaceIndex: Int = -1

    private var bytesIn: Long = 0
    private var bytesOut: Long = 0
    private var lastError: String? = null
    private var readEnabled = false
    private var probeSummary: String = "No probe run yet"
    private val aoapLog = ArrayDeque<String>()
    private var aoapAttemptedDeviceName: String? = null
    private var aoapReenumComplete = false
    private var aoapStartTimeMs = 0L
    private var lastAoapPollLogMs = 0L
    private var aoapPreDeviceName: String? = null
    private var aoapPreSignature: String? = null
    private var aoapPreVid = -1
    private var aoapPrePid = -1
    private var ignoredOriginalDeviceName: String? = null
    private var aoapPolling = false
    private var attachTimestampMs = 0L
    private var aaStartRequested = false
    private var aaStarted = false
    private var firstReadLogged = false
    private var firstWriteLogged = false
    private var expectedAccessoryDeviceName: String? = null
    private var aoapPendingAfterPermission = false
    private val delayedAaStart = Runnable {
        if (isReadyForIo() && !aaStarted) {
            logger("AA transport settled; notifying service to start AASDK")
            aaReadyListener?.invoke()
        } else {
            logger("AA delayed start skipped ready=${isReadyForIo()} aaStarted=$aaStarted")
        }
    }

    private data class AoapIdentity(
        val manufacturer: String,
        val model: String,
        val description: String,
        val version: String,
        val uri: String,
        val serial: String
    )

    private val GOOGLE_VID = 0x18D1
    private val ACCESSORY_PIDS = setOf(0x2D00, 0x2D01, 0x2D04, 0x2D05)

    fun startMonitoring() {
        updateDeviceCache()
        refreshStatus()
        scanAndOpenNow()
    }

    fun stopMonitoring() {
        close()
        setState(UsbState.IDLE)
        refreshStatus()
    }

    fun handleDeviceAttached(device: UsbDevice) {
        val wasWaiting = waitingForReenum()
        val preVid = lastSeenVid
        val prePid = lastSeenPid
        logger("USB attached ${device.deviceName} vid=${device.vendorId} pid=${device.productId}")
        updateDeviceCache(device)
        if (isAccessoryDevice(device)) {
            if (selectedDevice?.deviceName == device.deviceName &&
                connection != null &&
                claimedInterface != null &&
                state != UsbState.DISCONNECTED
            ) {
                logger("AUTO_START_TRIGGER: duplicate accessory attach ignored for ${device.deviceName}")
                refreshStatus()
                return
            }
            appendAoapLog("Accessory device detected (18D1:2Dxx); skipping AOAP negotiation")
            logger("AUTO_START_TRIGGER: accessory attached")
            aoapReenumComplete = true
            aoapAttemptedDeviceName = null
            aoapPreDeviceName = null
            aoapPreSignature = null
            aoapPreVid = -1
            aoapPrePid = -1
            ignoredOriginalDeviceName = null
            aoapPendingAfterPermission = false
            stopAoapPolling()
            lastSeenDeviceName = device.deviceName
            lastSeenVid = device.vendorId
            lastSeenPid = device.productId
            selectedDevice = device
            attachTimestampMs = System.currentTimeMillis()
            clearSelection()
            setState(UsbState.READY_FOR_AA)
            refreshStatus()
            if (!usbManager.hasPermission(device)) {
                logger("AUTO_START_TRIGGER: requesting permission for accessory")
                expectedAccessoryDeviceName = device.deviceName
                requestPermission(device)
            } else {
                logger("AUTO_START_TRIGGER: permission already granted")
                openDevice(device)
            }
            return
        }
        if (!wasWaiting) {
            lastSeenDeviceName = device.deviceName
            lastSeenVid = device.vendorId
            lastSeenPid = device.productId
            selectedDevice = device
            attachTimestampMs = System.currentTimeMillis()
            setState(UsbState.PRE_AA)
            refreshStatus()
        }
        if (wasWaiting) {
            val preSig = aoapPreSignature
            val newSig = deviceSignature(device)
            val signatureChanged = preSig != null && preSig != newSig
            val deviceNameChanged = device.deviceName != aoapPreDeviceName
            val vidPidChanged = device.vendorId != aoapPreVid || device.productId != aoapPrePid
            appendAoapLog(
                "AOAP re-enum candidate: ${device.deviceName} " +
                    "vid=${device.vendorId} pid=${device.productId} " +
                    "nameChanged=$deviceNameChanged vidPidChanged=$vidPidChanged sigChanged=$signatureChanged " +
                    "preVid=$aoapPreVid prePid=$aoapPrePid lastVid=$preVid lastPid=$prePid"
            )
            if (device.deviceName == ignoredOriginalDeviceName) {
                appendAoapLog("AOAP re-enum ignored: original deviceName")
                return
            }
            if (!deviceNameChanged) {
                appendAoapLog("AOAP re-enum ignored: deviceName unchanged")
                return
            }
            if (!vidPidChanged && !signatureChanged) {
                appendAoapLog("AOAP re-enum ignored: signature unchanged")
                return
            }
            appendAoapLog("AOAP re-enumeration accepted")
            aoapReenumComplete = true
            lastSeenDeviceName = device.deviceName
            lastSeenVid = device.vendorId
            lastSeenPid = device.productId
            selectedDevice = device
            attachTimestampMs = System.currentTimeMillis()
            aoapAttemptedDeviceName = null
            aoapPreDeviceName = null
            aoapPreSignature = null
            aoapPreVid = -1
            aoapPrePid = -1
            ignoredOriginalDeviceName = null
            stopAoapPolling()
            setState(UsbState.READY_FOR_AA)
            clearSelection()
            refreshStatus()
            if (usbManager.hasPermission(device)) {
                openDevice(device)
            } else {
                requestPermission(device)
            }
            return
        }
        if (aoapReenumComplete) {
            aoapAttemptedDeviceName = null
            scanAndOpenNow()
        } else {
            setState(UsbState.PRE_AA)
            refreshStatus()
        }
        if (wasWaiting && !aoapReenumComplete) {
            refreshStatus()
        }
    }

    fun handleDeviceDetached(device: UsbDevice) {
        logger("USB detached ${device.deviceName}")
        deviceCache.remove(device.deviceName)
        if (selectedDevice?.deviceName == device.deviceName ||
            (device.vendorId == lastSeenVid && device.productId == lastSeenPid)
        ) {
            close()
        }
        if (aoapAttemptedDeviceName == device.deviceName) {
            aoapAttemptedDeviceName = null
        }
        if (aoapPreDeviceName == device.deviceName) {
            aoapPreDeviceName = null
        }
        if (aoapPreSignature != null) {
            aoapPreSignature = null
        }
        aoapPendingAfterPermission = false
        aoapPreVid = -1
        aoapPrePid = -1
        if (ignoredOriginalDeviceName == device.deviceName) {
            ignoredOriginalDeviceName = null
        }
        setState(UsbState.DISCONNECTED)
        refreshStatus()
        retryOpenIfPossible()
        return
    }

    fun handlePermissionResult(device: UsbDevice, granted: Boolean) {
        updateDeviceCache(device)
        selectedDevice = device
        val expected = expectedAccessoryDeviceName
        logger(
            "USB_PERMISSION broadcast received: device=${device.deviceName} granted=$granted " +
                "expected=$expected match=${expected == null || expected == device.deviceName}"
        )
        if (expected != null && device.deviceName != expected) {
            appendAoapLog("USB_PERMISSION ignored: expected=$expected got=${device.deviceName}")
            return
        }
        if (waitingForReenum() && device.deviceName == ignoredOriginalDeviceName) {
            appendAoapLog("AOAP re-enum ignoring permission result for original device")
            return
        }
        if (isAccessoryDevice(device)) {
            aoapReenumComplete = true
            logger("AUTO_START_TRIGGER: permission granted")
        }
        if (granted) {
            logger("USB permission granted for ${device.deviceName}")
            pendingPermissionDeviceName = null
            logDeviceDescriptor(device)
            if (!aoapReenumComplete &&
                aoapPendingAfterPermission &&
                !isAccessoryDevice(device)
            ) {
                logger("AOAP permission granted; resuming accessory mode switch")
                aoapPendingAfterPermission = false
                enableAccessoryMode(device)
            } else if (aoapReenumComplete) {
                setState(UsbState.READY_FOR_AA)
                openDevice(device)
            } else {
                setState(UsbState.PRE_AA)
                refreshStatus()
            }
        } else {
            logger("USB permission denied for ${device.deviceName}")
            lastError = "USB permission denied"
            pendingPermissionDeviceName = null
            setState(UsbState.ERROR)
            refreshStatus()
        }
    }

    fun sendTestPacket() {
        if (!isReadyForIo()) {
            lastError = "USB write failed: endpoints not ready"
            refreshStatus()
            return
        }
        val payload = "AASDK-USB-TEST".toByteArray()
        val result = write(payload, payload.size, 1000)
        if (result <= 0) {
            logger("USB test write failed")
        }
    }

    fun probeEndpoints(): String {
        updateDeviceCache()
        val device = selectedDevice ?: deviceCache.values.firstOrNull { usbManager.hasPermission(it) }
        if (device == null) {
            probeSummary = "Probe failed: no permitted device"
            refreshStatus()
            return probeSummary
        }
        if (!usbManager.hasPermission(device)) {
            probeSummary = "Probe failed: permission required"
            refreshStatus()
            return probeSummary
        }
        val createdConnection = connection == null
        val conn = connection ?: usbManager.openDevice(device)
        if (conn == null) {
            probeSummary = "Probe failed: connection unavailable"
            refreshStatus()
            return probeSummary
        }
        val results = mutableListOf<String>()
        val payload = byteArrayOf(0x55, 0x53, 0x42, 0x50) // "USBP"
        var selected: EndpointSelection? = null
        for (sel in findBulkEndpointCandidates(device)) {
            val iface = sel.usbInterface
            val claimed = conn.claimInterface(iface, true)
            val result = if (claimed) {
                try {
                    conn.bulkTransfer(sel.outEndpoint, payload, payload.size, 1000)
                } catch (ex: Exception) {
                    logger(
                        "USB probe exception ${ex.javaClass.simpleName}: ${ex.message}\n" +
                            ex.stackTraceToString()
                    )
                    -99
                }
            } else {
                -98
            }
            results.add(
                "iface=${sel.interfaceIndex} out=${endpointSummary(sel.outEndpoint)} " +
                    "result=$result"
            )
            logger(
                "USB probe iface=${sel.interfaceIndex} out=${endpointSummary(sel.outEndpoint)} " +
                    "result=$result"
            )
            if (result >= 0 && selected == null) {
                selected = sel
            }
            if (claimed) {
                conn.releaseInterface(iface)
            }
        }
        if (selected != null) {
            probeSummary = "Probe candidate iface=${selected.interfaceIndex} out=${endpointSummary(selected.outEndpoint)}"
        } else {
            probeSummary = "Probe found no writable endpoints"
        }
        if (createdConnection) {
            conn.close()
        }
        refreshStatus()
        return results.joinToString(" | ").ifBlank { probeSummary }
    }

    fun getSnapshot(): UsbStatusSnapshot = buildSnapshot()

    fun selectDeviceForAa(deviceName: String): Boolean {
        updateDeviceCache()
        val device = deviceCache[deviceName]
        if (device == null) {
            lastError = "Device not found: $deviceName"
            refreshStatus()
            return false
        }
        logger("USB selected for AA ${device.deviceName} vid=${device.vendorId} pid=${device.productId}")
        lastSeenDeviceName = device.deviceName
        lastSeenVid = device.vendorId
        lastSeenPid = device.productId
        selectedDevice = device
        attachTimestampMs = System.currentTimeMillis()
        expectedAccessoryDeviceName = null
        if (isAccessoryDevice(device)) {
            aoapReenumComplete = true
            aoapAttemptedDeviceName = null
            aoapPendingAfterPermission = false
            setState(UsbState.READY_FOR_AA)
            refreshStatus()
            if (usbManager.hasPermission(device)) {
                openDevice(device)
            } else {
                requestPermission(device)
            }
            return true
        }
        aoapReenumComplete = false
        aoapAttemptedDeviceName = null
        aoapPendingAfterPermission = false
        setState(UsbState.PRE_AA)
        refreshStatus()
        return enableAccessoryMode()
    }

    fun openByDeviceName(deviceName: String): Boolean {
        val device = deviceCache[deviceName]
        if (device == null) {
            lastError = "Device not found: $deviceName"
            refreshStatus()
            return false
        }
        lastSeenDeviceName = device.deviceName
        lastSeenVid = device.vendorId
        lastSeenPid = device.productId
        return openDevice(device)
    }

    fun openByVidPid(vid: Int, pid: Int): Boolean {
        val device = deviceCache.values.firstOrNull { it.vendorId == vid && it.productId == pid }
        if (device == null) {
            lastError = "Device not found: $vid:$pid"
            refreshStatus()
            return false
        }
        lastSeenDeviceName = device.deviceName
        lastSeenVid = device.vendorId
        lastSeenPid = device.productId
        return openDevice(device)
    }

    fun readOnce(buffer: ByteArray, timeoutMs: Int): Int {
        val inEp = inEndpoint
        val conn = connection
        if (inEp == null || conn == null) {
            return -1
        }
        externalReadActive.set(true)
        return try {
            val read = conn.bulkTransfer(inEp, buffer, buffer.size, timeoutMs)
            if (read > 0) {
                bytesIn += read
                lastError = null
                if (!firstReadLogged) {
                    firstReadLogged = true
                    logger("USB first IN read len=$read timeoutMs=$timeoutMs")
                }
                if (state != UsbState.AA_SESSION_ACTIVE) {
                    setState(UsbState.AA_SESSION_ACTIVE)
                }
                refreshStatus()
            }
            read
        } catch (ex: Exception) {
            LogFileHelper.appendException(context, "USB read exception", ex)
            logger(
                "USB read exception ${ex.javaClass.simpleName}: ${ex.message}\n" +
                    ex.stackTraceToString()
            )
            lastError = "USB read exception"
            refreshStatus()
            -99
        } finally {
            externalReadActive.set(false)
        }
    }

    fun write(buffer: ByteArray, len: Int, timeoutMs: Int): Int {
        val outEp = outEndpoint
        val conn = connection
        if (outEp == null || conn == null || claimedInterface == null) {
            lastError = "USB write failed: no OUT endpoint"
            logger(
                "USB write blocked conn=${conn != null} iface=${claimedInterface != null} " +
                    "outEp=${outEp != null} len=$len timeoutMs=$timeoutMs"
            )
            refreshStatus()
            return -1
        }
        val writeLen = len.coerceAtMost(buffer.size)
        val result = try {
            conn.bulkTransfer(outEp, buffer, writeLen, timeoutMs)
        } catch (ex: Exception) {
            LogFileHelper.appendException(context, "USB write exception", ex)
            logger(
                "USB write exception ${ex.javaClass.simpleName}: ${ex.message}\n" +
                    ex.stackTraceToString()
            )
            lastError = "USB write exception"
            refreshStatus()
            return -99
        }
        if (result > 0) {
            bytesOut += result
            lastError = null
            writeTimeoutCount = 0
            if (!firstWriteLogged) {
                firstWriteLogged = true
                logger(
                    "USB first OUT write attempt result=$result iface=$selectedInterfaceIndex " +
                        "ep=${endpointSummary(outEp)} len=$writeLen timeoutMs=$timeoutMs"
                )
            }
            refreshStatus()
        } else if (result < 0) {
            if (result == -1) {
                writeTimeoutCount += 1
                lastError = "USB write timeout"
                if (writeTimeoutCount == 1 || writeTimeoutCount % 20 == 0) {
                    logger(
                        "USB write timeout code=$result iface=$selectedInterfaceIndex " +
                            "ep=${endpointSummary(outEp)} len=$writeLen timeoutMs=$timeoutMs count=$writeTimeoutCount"
                    )
                }
                if (!firstWriteLogged) {
                    firstWriteLogged = true
                    logger(
                        "USB first OUT write attempt result=$result iface=$selectedInterfaceIndex " +
                            "ep=${endpointSummary(outEp)} len=$writeLen timeoutMs=$timeoutMs"
                    )
                }
                if (writeTimeoutCount >= 3) {
                    if (isAaSessionInProgress()) {
                        logger(
                            "USB write timeout persists during AA session; " +
                                "suppressing reopen state=$state aaStarted=$aaStarted aaStartRequested=$aaStartRequested"
                        )
                    } else {
                        logger("USB write timeout persists, retrying open")
                        retryOpenIfPossible()
                    }
                }
            } else {
                lastError = "USB write error"
                logger(
                    "USB write error code=$result iface=$selectedInterfaceIndex " +
                        "ep=${endpointSummary(outEp)} len=$writeLen timeoutMs=$timeoutMs"
                )
                if (!firstWriteLogged) {
                    firstWriteLogged = true
                    logger(
                        "USB first OUT write attempt result=$result iface=$selectedInterfaceIndex " +
                            "ep=${endpointSummary(outEp)} len=$writeLen timeoutMs=$timeoutMs"
                    )
                }
            }
            refreshStatus()
        } else {
            logger(
                "USB write returned 0 iface=$selectedInterfaceIndex ep=${endpointSummary(outEp)} " +
                    "len=$writeLen timeoutMs=$timeoutMs"
            )
            if (!firstWriteLogged) {
                firstWriteLogged = true
                logger(
                    "USB first OUT write attempt result=$result iface=$selectedInterfaceIndex " +
                        "ep=${endpointSummary(outEp)} len=$writeLen timeoutMs=$timeoutMs"
                )
            }
        }
        return result
    }

    fun close() {
        val wasAaSession = isAaSessionInProgress()
        mainHandler.removeCallbacks(delayedAaStart)
        if (connection != null) {
            logger("USB closing connection")
        }
        stopReadLoop()
        if (connection != null && claimedInterface != null) {
            connection?.releaseInterface(claimedInterface)
        }
        connection?.close()
        connection = null
        claimedInterface = null
        inEndpoint = null
        outEndpoint = null
        selectedInterfaceIndex = -1
        selectedDevice = null
        readEnabled = false
        writeTimeoutCount = 0
        aaStartRequested = false
        aaStarted = false
        firstReadLogged = false
        firstWriteLogged = false
        if (wasAaSession) {
            setState(UsbState.DISCONNECTED)
            mainHandler.post {
                sessionClosedListener?.invoke()
            }
        }
        refreshStatus()
    }

    fun onTransportStalled() {
        mainHandler.removeCallbacks(delayedAaStart)
        LogFileHelper.appendEvent(
            context,
            "UsbIoController",
            "USB transport stalled; keeping accessory open state=$state selected=${selectedDevice?.deviceName ?: "none"}"
        )
        logger(
            "USB transport stalled during AA; keeping accessory open " +
                "state=$state selected=${selectedDevice?.deviceName ?: "none"}"
        )
        stopReadLoop()
        readEnabled = false
        writeTimeoutCount = 0
        aaStartRequested = false
        aaStarted = false
        firstReadLogged = false
        firstWriteLogged = false
        lastError = "AA transport stalled"
        if (isReadyForIo()) {
            setState(UsbState.READY_FOR_AA)
        } else {
            setState(UsbState.DISCONNECTED)
        }
        refreshStatus()
        mainHandler.post {
            sessionStalledListener?.invoke()
        }
    }

    fun getDeviceSummary(): String {
        updateDeviceCache()
        val devices = deviceCache.values
        if (devices.isEmpty()) {
            return "No USB devices detected."
        }
        val lines = mutableListOf<String>()
        for (device in devices) {
            lines.add(describeDevice(device))
            lines.addAll(formatDeviceDetails(device))
        }
        return lines.joinToString("\n")
    }

    private fun openPreferredIfPossible() {
        updateDeviceCache()
        val preferredName = prefs.getString("last_device_name", null)
        val preferredVid = prefs.getInt("last_vid", -1)
        val preferredPid = prefs.getInt("last_pid", -1)
        val candidate = when {
            preferredName != null -> deviceCache[preferredName]
            preferredVid >= 0 && preferredPid >= 0 -> deviceCache.values.firstOrNull {
                it.vendorId == preferredVid && it.productId == preferredPid
            }
            else -> null
        } ?: deviceCache.values.firstOrNull()
        if (candidate != null) {
            if (usbManager.hasPermission(candidate)) {
                openDevice(candidate)
            } else {
                requestPermission(candidate)
            }
        }
    }

    private fun openDevice(device: UsbDevice): Boolean {
        if (!usbManager.hasPermission(device)) {
            requestPermission(device)
            return false
        }
        if (!aoapReenumComplete) {
            lastError = "Waiting for AOAP re-enumeration"
            refreshStatus()
            return false
        }
        if (!isAccessoryDevice(device)) {
            lastError = "Not in accessory mode (18D1:2Dxx)"
            setState(UsbState.PRE_AA)
            refreshStatus()
            return false
        }
        if (selectedDevice?.deviceName == device.deviceName &&
            connection != null &&
            claimedInterface != null &&
            inEndpoint != null &&
            outEndpoint != null
        ) {
            logger("USB open ignored; accessory already open ${device.deviceName}")
            setState(UsbState.READY_FOR_AA)
            refreshStatus()
            return true
        }
        close()
        selectedDevice = device
        lastError = null
        logger("USB open ${device.deviceName}")
        logDeviceDescriptor(device)
        val conn = usbManager.openDevice(device)
        if (conn == null) {
            lastError = "Failed to open device"
            setState(UsbState.ERROR)
            refreshStatus()
            return false
        }
        connection = conn
        val candidates = findBulkEndpointCandidates(device)
        if (candidates.isEmpty()) {
            lastError = "No accessory bulk endpoints found"
            logger("USB no accessory bulk endpoints found")
            setState(UsbState.ERROR)
            close()
            return false
        }
        for (candidate in candidates) {
            logger(
                "USB candidate iface=${candidate.interfaceIndex} score=${candidate.score} " +
                    "class=${candidate.usbInterface.interfaceClass} " +
                    "sub=${candidate.usbInterface.interfaceSubclass} " +
                    "proto=${candidate.usbInterface.interfaceProtocol} " +
                    "interruptIn=${candidate.hasInterruptIn} " +
                    "in=${endpointSummary(candidate.inEndpoint)} " +
                    "out=${endpointSummary(candidate.outEndpoint)}"
            )
        }
        val selected = candidates.maxByOrNull { it.score }!!
        bindSelection(selected)
        if (!conn.claimInterface(claimedInterface, true)) {
            lastError = "Failed to claim interface"
            logger("USB failed to claim interface ${claimedInterface?.id}")
            setState(UsbState.ERROR)
            close()
            return false
        }
        logger("USB interface claimed index=$selectedInterfaceIndex id=${claimedInterface?.id}")
        logger(
            "USB endpoints IN=${endpointSummary(inEndpoint)} OUT=${endpointSummary(outEndpoint)}"
        )
        logger(
            "AA transport ready; epIn=${endpointSummary(inEndpoint)} " +
                "epOut=${endpointSummary(outEndpoint)}"
        )
        prefs.edit()
            .putString("last_device_name", device.deviceName)
            .putInt("last_vid", device.vendorId)
            .putInt("last_pid", device.productId)
            .apply()
        bytesIn = 0
        bytesOut = 0
        readEnabled = false
        firstReadLogged = false
        firstWriteLogged = false
        setState(UsbState.READY_FOR_AA)
        refreshStatus()
        logger(
            "AA transport ready; scheduling AASDK start in ${Constants.AA_START_DELAY_MS}ms"
        )
        mainHandler.removeCallbacks(delayedAaStart)
        mainHandler.postDelayed(delayedAaStart, Constants.AA_START_DELAY_MS)
        return true
    }

    private data class EndpointSelection(
        val interfaceIndex: Int,
        val usbInterface: UsbInterface,
        val inEndpoint: UsbEndpoint,
        val outEndpoint: UsbEndpoint,
        val hasInterruptIn: Boolean,
        val score: Int
    )

    private fun findBulkEndpoints(device: UsbDevice): EndpointSelection? {
        val candidates = findBulkEndpointCandidates(device)
        if (candidates.isEmpty()) {
            return null
        }
        return candidates.firstOrNull { it.usbInterface.interfaceClass == 0xFF }
    }

    private fun findBulkEndpointCandidates(device: UsbDevice): List<EndpointSelection> {
        val candidates = mutableListOf<EndpointSelection>()
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            logger(
                "USB interface index=$i id=${usbInterface.id} class=${usbInterface.interfaceClass} " +
                    "sub=${usbInterface.interfaceSubclass} proto=${usbInterface.interfaceProtocol} " +
                    "endpoints=${usbInterface.endpointCount}"
            )
            if (usbInterface.endpointCount < 2) {
                continue
            }
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            var bulkInCount = 0
            var bulkOutCount = 0
            var interruptIn = false
            for (e in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(e)
                logger("USB endpoint candidate ${endpointSummary(endpoint)}")
                if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                        bulkInCount += 1
                        inEp = endpoint
                    } else if (endpoint.direction == UsbConstants.USB_DIR_OUT) {
                        bulkOutCount += 1
                        outEp = endpoint
                    }
                } else if (
                    endpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT &&
                    endpoint.direction == UsbConstants.USB_DIR_IN
                ) {
                    interruptIn = true
                }
            }
            if (bulkInCount >= 1 && bulkOutCount >= 1 && inEp != null && outEp != null) {
                candidates.add(
                    EndpointSelection(
                        interfaceIndex = i,
                        usbInterface = usbInterface,
                        inEndpoint = inEp,
                        outEndpoint = outEp,
                        hasInterruptIn = interruptIn,
                        score = scoreEndpointSelection(usbInterface, interruptIn)
                    )
                )
            }
        }
        return candidates
    }

    private fun scoreEndpointSelection(usbInterface: UsbInterface, hasInterruptIn: Boolean): Int {
        var score = 0
        if (usbInterface.interfaceClass == 0xFF) {
            score += 100
        }
        if (usbInterface.interfaceSubclass == 0xFF) {
            score += 20
        }
        if (usbInterface.interfaceProtocol == 0) {
            score += 10
        }
        if (hasInterruptIn) {
            score += 5
        }
        return score
    }

    private fun startReadLoop() {
        if (readRunning.getAndSet(true)) return
        readThread = Thread {
            while (readRunning.get()) {
                try {
                    if (!readEnabled) {
                        Thread.sleep(100)
                        continue
                    }
                    if (externalReadActive.get()) {
                        Thread.sleep(10)
                        continue
                    }
                } catch (ex: InterruptedException) {
                    return@Thread
                }
                try {
                    Thread.sleep(200)
                } catch (ex: InterruptedException) {
                    return@Thread
                }
            }
        }
        readThread?.isDaemon = true
        readThread?.start()
    }

    private fun stopReadLoop() {
        readRunning.set(false)
        readThread?.interrupt()
        readThread = null
    }

    private fun requestPermission(device: UsbDevice) {
        if (pendingPermissionDeviceName == device.deviceName) {
            return
        }
        pendingPermissionDeviceName = device.deviceName
        logger("USB request permission for ${device.deviceName}")
        if (isAccessoryDevice(device)) {
            expectedAccessoryDeviceName = device.deviceName
        }
        val intent = Intent(Constants.USB_PERMISSION)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
        usbManager.requestPermission(device, pendingIntent)
    }

    private fun refreshStatus() {
        val snapshot = buildSnapshot()
        statusListener(
            snapshot
        )
    }

    private fun describeDevice(device: UsbDevice): String {
        val manuf = try {
            device.manufacturerName ?: "unknown"
        } catch (ex: SecurityException) {
            "unknown"
        }
        val product = try {
            device.productName ?: "unknown"
        } catch (ex: SecurityException) {
            "unknown"
        }
        return "${device.deviceName} vid=${device.vendorId} pid=${device.productId} $manuf/$product"
    }

    private fun endpointSummary(endpoint: UsbEndpoint?): String {
        if (endpoint == null) return "none"
        val dir = if (endpoint.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
        val addr = String.format(Locale.US, "0x%02X", endpoint.address)
        val type = endpointTypeName(endpoint.type)
        return "$dir addr=$addr type=$type max=${endpoint.maxPacketSize}"
    }

    private fun deviceSignature(device: UsbDevice): String {
        val parts = StringBuilder()
        parts.append("ifaces=").append(device.interfaceCount)
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            parts.append("|i").append(i)
            parts.append(":c").append(iface.interfaceClass)
            parts.append(":s").append(iface.interfaceSubclass)
            parts.append(":p").append(iface.interfaceProtocol)
            parts.append(":e").append(iface.endpointCount)
        }
        return parts.toString()
    }

    private fun formatDeviceDetails(device: UsbDevice): List<String> {
        val lines = mutableListOf<String>()
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            lines.add(
                "  iface index=$i id=${usbInterface.id} class=${usbInterface.interfaceClass} " +
                    "sub=${usbInterface.interfaceSubclass} proto=${usbInterface.interfaceProtocol} " +
                    "endpoints=${usbInterface.endpointCount}"
            )
            for (e in 0 until usbInterface.endpointCount) {
                val ep = usbInterface.getEndpoint(e)
                lines.add("    ep ${endpointSummary(ep)}")
            }
        }
        return lines
    }

    private fun countBulkCandidates(device: UsbDevice): Triple<Int, Int, Int> {
        var bulkIn = 0
        var bulkOut = 0
        val ifaceCount = device.interfaceCount
        for (i in 0 until ifaceCount) {
            val usbInterface = device.getInterface(i)
            for (e in 0 until usbInterface.endpointCount) {
                val ep = usbInterface.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) {
                        bulkIn++
                    } else if (ep.direction == UsbConstants.USB_DIR_OUT) {
                        bulkOut++
                    }
                }
            }
        }
        return Triple(ifaceCount, bulkIn, bulkOut)
    }

    private fun logDeviceDescriptor(device: UsbDevice) {
        logger("USB descriptor dump for ${device.deviceName}")
        for (line in formatDeviceDetails(device)) {
            logger(line)
        }
    }

    private fun isReadyForIo(): Boolean {
        return connection != null && claimedInterface != null && inEndpoint != null && outEndpoint != null
    }

    private fun isAaSessionInProgress(): Boolean {
        return aaStartRequested ||
            aaStarted ||
            state == UsbState.STARTING_AA ||
            state == UsbState.AA_TLS_HANDSHAKE ||
            state == UsbState.AA_SESSION_ACTIVE
    }

    private fun bindSelection(selection: EndpointSelection) {
        claimedInterface = selection.usbInterface
        selectedInterfaceIndex = selection.interfaceIndex
        inEndpoint = selection.inEndpoint
        outEndpoint = selection.outEndpoint
    }

    private fun clearSelection() {
        stopReadLoop()
        connection?.close()
        connection = null
        claimedInterface = null
        inEndpoint = null
        outEndpoint = null
        selectedInterfaceIndex = -1
        readEnabled = false
        aaStartRequested = false
        aaStarted = false
    }

    private fun waitingForReenum(): Boolean {
        return state == UsbState.WAITING_FOR_AOAP_REENUMERATION
    }

    private fun logDeviceList(prefix: String) {
        val devices = usbManager.deviceList.values
        if (devices.isEmpty()) {
            logger("$prefix: no devices")
            return
        }
        for (device in devices) {
            logger(
                "$prefix: ${device.deviceName} vid=${device.vendorId} pid=${device.productId} " +
                    "ifaces=${device.interfaceCount}"
            )
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                logger(
                    "$prefix: iface=$i class=${iface.interfaceClass} " +
                        "sub=${iface.interfaceSubclass} proto=${iface.interfaceProtocol} " +
                        "endpoints=${iface.endpointCount}"
                )
                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    logger("$prefix:   ep ${endpointSummary(ep)}")
                }
            }
        }
    }

    private fun startAoapPolling() {
        if (aoapPolling) return
        aoapPolling = true
        lastAoapPollLogMs = 0L
        pollAoapReenum()
    }

    private fun stopAoapPolling() {
        aoapPolling = false
    }

    private fun pollAoapReenum() {
        if (!aoapPolling) return
        if (!waitingForReenum()) {
            aoapPolling = false
            return
        }
        val now = System.currentTimeMillis()
        if (now - aoapStartTimeMs > 10_000L) {
            appendAoapLog("AOAP re-enumeration timeout")
            lastError = "AOAP re-enumeration timeout"
            aoapPolling = false
            setState(UsbState.ERROR)
            refreshStatus()
            return
        }
        if (now - lastAoapPollLogMs >= 1000L) {
            lastAoapPollLogMs = now
            logDeviceList("AOAP waiting")
        }
        val devices = usbManager.deviceList.values
        for (device in devices) {
            val preSig = aoapPreSignature
            val newSig = deviceSignature(device)
            val signatureChanged = preSig != null && preSig != newSig
            val deviceNameChanged = device.deviceName != aoapPreDeviceName
            val vidPidChanged = device.vendorId != aoapPreVid || device.productId != aoapPrePid
            if (device.deviceName == ignoredOriginalDeviceName) {
                continue
            }
            if (!deviceNameChanged) {
                continue
            }
            if (!vidPidChanged && !signatureChanged) {
                continue
            }
            appendAoapLog(
                "Latched new device: $ignoredOriginalDeviceName -> ${device.deviceName} " +
                    "oldSig=$aoapPreSignature newSig=$newSig"
            )
            aoapReenumComplete = true
            lastSeenDeviceName = device.deviceName
            lastSeenVid = device.vendorId
            lastSeenPid = device.productId
            selectedDevice = device
            aoapAttemptedDeviceName = null
            aoapPreDeviceName = null
            aoapPreSignature = null
            aoapPreVid = -1
            aoapPrePid = -1
            ignoredOriginalDeviceName = null
            aoapPolling = false
            setState(UsbState.READY_FOR_AA)
            refreshStatus()
            if (usbManager.hasPermission(device)) {
                openDevice(device)
            } else {
                requestPermission(device)
            }
            return
        }
        mainHandler.postDelayed({ pollAoapReenum() }, 150L)
    }

    private fun retryOpenIfPossible() {
        if (isAaSessionInProgress()) {
            logger(
                "USB retry suppressed during AA session " +
                    "state=$state aaStarted=$aaStarted aaStartRequested=$aaStartRequested"
            )
            return
        }
        updateDeviceCache()
        val target = deviceCache.values.firstOrNull {
            it.vendorId == lastSeenVid && it.productId == lastSeenPid
        } ?: return
        if (usbManager.hasPermission(target)) {
            if (aoapReenumComplete || isAccessoryDevice(target)) {
                openDevice(target)
            } else {
                setState(UsbState.PRE_AA)
                refreshStatus()
            }
        } else {
            requestPermission(target)
        }
    }

    private fun buildSnapshot(): UsbStatusSnapshot {
        updateDeviceCache()
        val devices = deviceCache.values
        val devicesSummary = if (devices.isEmpty()) {
            "No USB devices"
        } else {
            devices.joinToString("\n") { describeDevice(it) }
        }
        val permissionSummary = selectedDevice?.let {
            if (usbManager.hasPermission(it)) {
                "Permission granted"
            } else {
                "Permission required"
            }
        } ?: "No device selected"
        val selectionSummary = selectedDevice?.let {
            val ifaceIndex = if (selectedInterfaceIndex >= 0) selectedInterfaceIndex.toString() else "none"
            val ifaceClass = claimedInterface?.interfaceClass
            val ifaceSub = claimedInterface?.interfaceSubclass
            val ifaceProto = claimedInterface?.interfaceProtocol
            val ifaceDetails = if (ifaceClass != null) {
                " class=$ifaceClass sub=$ifaceSub proto=$ifaceProto"
            } else {
                ""
            }
            "${it.deviceName} vid=${it.vendorId} pid=${it.productId} ifaceIndex=$ifaceIndex$ifaceDetails"
        } ?: "None"
        val endpointsSummary = if (inEndpoint != null && outEndpoint != null) {
            "IN ${endpointSummary(inEndpoint)} OUT ${endpointSummary(outEndpoint)}"
        } else {
            "None"
        }
        val accessoryDevice = devices.firstOrNull { isAccessoryDevice(it) }
        val accessorySummary = if (accessoryDevice != null) {
            "vid=${accessoryDevice.vendorId} pid=${accessoryDevice.productId}"
        } else {
            "Not detected"
        }
        val ready = isReadyForIo()
        val summaryDevice = selectedDevice ?: devices.firstOrNull()
        val counts = if (summaryDevice != null) {
            countBulkCandidates(summaryDevice)
        } else {
            Triple(0, 0, 0)
        }
        return UsbStatusSnapshot(
            devicesSummary = devicesSummary,
            permissionSummary = permissionSummary,
            selectionSummary = selectionSummary,
            endpointsSummary = endpointsSummary,
            accessorySummary = accessorySummary,
            interfaceCount = counts.first,
            bulkInCount = counts.second,
            bulkOutCount = counts.third,
            readyForIo = ready,
            probeSummary = probeSummary,
            stateSummary = state.name,
            bytesIn = bytesIn,
            bytesOut = bytesOut,
            lastError = lastError
        )
    }

    private fun scanAndOpenNow() {
        updateDeviceCache()
        if (!aoapReenumComplete) {
            val accessory = deviceCache.values.firstOrNull { isAccessoryDevice(it) }
            if (accessory != null) {
                aoapReenumComplete = true
                selectedDevice = accessory
                setState(UsbState.READY_FOR_AA)
            }
        }
        if (waitingForReenum()) {
            startAoapPolling()
            return
        }
        val permitted = deviceCache.values.filter { usbManager.hasPermission(it) }
        if (permitted.isNotEmpty()) {
            if (aoapReenumComplete) {
                val target = selectedDevice ?: permitted.firstOrNull { isAccessoryDevice(it) }
                    ?: permitted.first()
                if (!openDevice(target)) {
                    scheduleRetry()
                }
                return
            }
            setState(UsbState.PRE_AA)
            refreshStatus()
            return
        }
        if (deviceCache.isNotEmpty()) {
            setState(UsbState.PRE_AA)
            refreshStatus()
        }
    }

    private fun scheduleRetry() {
        if (retryScheduled) return
        retryScheduled = true
        mainHandler.postDelayed({
            retryScheduled = false
            if (state != UsbState.READY_FOR_AA &&
                state != UsbState.AA_TLS_HANDSHAKE &&
                state != UsbState.AA_SESSION_ACTIVE) {
                scanAndOpenNow()
            }
        }, 1000)
    }

    private fun attemptAoap(device: UsbDevice) {
        if (state != UsbState.PRE_AA && state != UsbState.AOAP_NEGOTIATING) {
            appendAoapLog("AOAP blocked: state=$state")
            return
        }
        if (isAccessoryDevice(device)) {
            aoapReenumComplete = true
            selectedDevice = device
            setState(UsbState.READY_FOR_AA)
            scanAndOpenNow()
            return
        }
        if (aoapAttemptedDeviceName == device.deviceName) {
            setState(UsbState.AOAP_NEGOTIATING)
            return
        }
        aoapAttemptedDeviceName = device.deviceName
        setState(UsbState.AOAP_NEGOTIATING)
        if (!usbManager.hasPermission(device)) {
            aoapPendingAfterPermission = true
            requestPermission(device)
            return
        }
        aoapPendingAfterPermission = false
        enableAccessoryMode(device)
    }

    fun enableAccessoryMode(): Boolean {
        updateDeviceCache()
        val device = selectedDevice ?: deviceCache.values.firstOrNull()
        return if (device != null) {
            if (isAccessoryDevice(device)) {
                appendAoapLog("AOAP blocked: already in accessory mode")
                return false
            }
            if (state != UsbState.PRE_AA && state != UsbState.AOAP_NEGOTIATING) {
                appendAoapLog("AOAP blocked: state=$state")
                return false
            }
            if (!usbManager.hasPermission(device)) {
                appendAoapLog("AOAP permission required for ${device.deviceName}")
                aoapPendingAfterPermission = true
                requestPermission(device)
                return false
            }
            aoapAttemptedDeviceName = device.deviceName
            aoapPendingAfterPermission = false
            enableAccessoryMode(device)
        } else {
            logger("AOAP failed: no USB device")
            false
        }
    }

    private fun enableAccessoryMode(device: UsbDevice): Boolean {
        updateDeviceCache()
        if (state == UsbState.READY_FOR_AA ||
            state == UsbState.AA_TLS_HANDSHAKE ||
            state == UsbState.AA_SESSION_ACTIVE
        ) {
            appendAoapLog("AOAP ERROR: called during AA state=$state")
            return false
        }
        if (!usbManager.hasPermission(device)) {
            logger("AOAP permission required for ${device.deviceName}")
            aoapPendingAfterPermission = true
            requestPermission(device)
            return false
        }
        aoapPendingAfterPermission = false
        val conn = usbManager.openDevice(device)
        if (conn == null) {
            logger("AOAP failed: could not open device ${device.deviceName}")
            return false
        }
        try {
            val identity = loadAoapIdentity()
            val protoBuffer = ByteArray(2)
            val getProtocolReqType = UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_VENDOR
            val getProtocolResult = conn.controlTransfer(
                getProtocolReqType,
                51,
                0,
                0,
                protoBuffer,
                protoBuffer.size,
                1000
            )
            appendAoapLog(
                "AOAP GET_PROTOCOL reqType=${hexByte(getProtocolReqType)} req=51 value=0 index=0 " +
                    "len=2 result=$getProtocolResult data=${hexDump(protoBuffer, protoBuffer.size)}"
            )
            if (getProtocolResult < 0) {
                lastError = "AOAP GET_PROTOCOL failed"
                refreshStatus()
                return false
            }
            appendAoapLog("AOAP identity in use:")
            appendAoapLog("  [0] manufacturer=\"${identity.manufacturer}\"")
            appendAoapLog("  [1] model=\"${identity.model}\"")
            appendAoapLog("  [2] description=\"${identity.description}\"")
            appendAoapLog("  [3] version=\"${identity.version}\"")
            appendAoapLog("  [4] uri=\"${identity.uri}\"")
            appendAoapLog("  [5] serial=\"${identity.serial}\"")
            val strings = listOf(
                identity.manufacturer,
                identity.model,
                identity.description,
                identity.version,
                identity.uri,
                identity.serial
            )
            for (i in strings.indices) {
                val raw = strings[i].toByteArray(Charsets.UTF_8)
                val data = ByteArray(raw.size + 1)
                System.arraycopy(raw, 0, data, 0, raw.size)
                data[data.size - 1] = 0x00
                val sendStringReqType = UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_VENDOR
                val result = conn.controlTransfer(
                    sendStringReqType,
                    52,
                    0,
                    i,
                    data,
                    data.size,
                    1000
                )
                appendAoapLog(
                    "AOAP SEND_STRING reqType=${hexByte(sendStringReqType)} req=52 index=$i " +
                        "len=${data.size} result=$result value=\"${strings[i]}\" data=${hexDump(data, data.size)}"
                )
                if (result < 0) {
                    lastError = "AOAP SEND_STRING failed index=$i"
                    refreshStatus()
                    return false
                }
            }
            val startReqType = UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_VENDOR
            val startResult = conn.controlTransfer(
                startReqType,
                53,
                0,
                0,
                null,
                0,
                1000
            )
            appendAoapLog(
                "AOAP START_ACCESSORY reqType=${hexByte(startReqType)} req=53 value=0 index=0 " +
                    "len=0 result=$startResult"
            )
            if (startResult < 0) {
                lastError = "AOAP START_ACCESSORY failed"
                refreshStatus()
                return false
            }
            appendAoapLog("AOAP start requested, waiting for re-enumeration")
            aoapStartTimeMs = System.currentTimeMillis()
            aoapPreDeviceName = device.deviceName
            aoapPreSignature = deviceSignature(device)
            aoapPreVid = device.vendorId
            aoapPrePid = device.productId
            ignoredOriginalDeviceName = device.deviceName
            aoapReenumComplete = false
            aoapPendingAfterPermission = false
            clearSelection()
            setState(UsbState.WAITING_FOR_AOAP_REENUMERATION)
            refreshStatus()
            startAoapPolling()
            return true
        } catch (ex: Exception) {
            appendAoapLog("AOAP exception ${ex.javaClass.simpleName}: ${ex.message}")
            lastError = "AOAP exception"
            refreshStatus()
            return false
        } finally {
            conn.close()
        }
    }

    fun getAoapLog(): List<String> {
        return aoapLog.toList()
    }

    fun markTlsHandshake() {
        if (state == UsbState.READY_FOR_AA) {
            setState(UsbState.AA_TLS_HANDSHAKE)
            refreshStatus()
        }
    }

    fun requestAaStart(): Boolean {
        aaStartRequested = true
        return !aaStarted
    }

    fun markAaStarted() {
        aaStarted = true
        readEnabled = false
        stopReadLoop()
    }

    fun markAaStarting() {
        setState(UsbState.STARTING_AA)
        refreshStatus()
    }

    fun getAoapIdentitySummary(): List<String> {
        val identity = loadAoapIdentity()
        return listOf(
            "manufacturer=\"${identity.manufacturer}\"",
            "model=\"${identity.model}\"",
            "description=\"${identity.description}\"",
            "version=\"${identity.version}\"",
            "uri=\"${identity.uri}\"",
            "serial=\"${identity.serial}\""
        )
    }


    private fun loadAoapIdentity(): AoapIdentity {
        val prefs = context.getSharedPreferences(Constants.AOAP_PREFS, Context.MODE_PRIVATE)
        return AoapIdentity(
            manufacturer = Constants.normalizeAoapValue(
                Constants.AOAP_MANUFACTURER,
                prefs.getString(Constants.AOAP_MANUFACTURER, null)
            ),
            model = Constants.normalizeAoapValue(
                Constants.AOAP_MODEL,
                prefs.getString(Constants.AOAP_MODEL, null)
            ),
            description = Constants.normalizeAoapValue(
                Constants.AOAP_DESCRIPTION,
                prefs.getString(Constants.AOAP_DESCRIPTION, null)
            ),
            version = Constants.normalizeAoapValue(
                Constants.AOAP_VERSION,
                prefs.getString(Constants.AOAP_VERSION, null)
            ),
            uri = Constants.normalizeAoapValue(
                Constants.AOAP_URI,
                prefs.getString(Constants.AOAP_URI, null)
            ),
            serial = Constants.normalizeAoapValue(
                Constants.AOAP_SERIAL,
                prefs.getString(Constants.AOAP_SERIAL, null)
            )
        )
    }

    private fun endpointTypeName(type: Int): String {
        return when (type) {
            UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
            UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "control"
            UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
            UsbConstants.USB_ENDPOINT_XFER_ISOC -> "iso"
            else -> type.toString()
        }
    }

    private fun hexByte(value: Int): String {
        return String.format(Locale.US, "0x%02X", value and 0xFF)
    }

    private fun updateDeviceCache(newDevice: UsbDevice? = null) {
        if (newDevice != null) {
            deviceCache[newDevice.deviceName] = newDevice
            return
        }
        val devices = usbManager.deviceList
        if (devices.isEmpty()) {
            deviceCache.clear()
            return
        }
        deviceCache.clear()
        for ((name, device) in devices) {
            deviceCache[name] = device
        }
    }

    private fun isAccessoryDevice(device: UsbDevice): Boolean {
        return device.vendorId == GOOGLE_VID && ACCESSORY_PIDS.contains(device.productId)
    }

    private fun appendAoapLog(message: String) {
        aoapLog.add(message)
        if (aoapLog.size > 200) {
            aoapLog.removeFirst()
        }
        logger(message)
    }

    private fun setState(newState: UsbState) {
        if (state != newState) {
            state = newState
            logger("USB state -> $newState")
        }
    }

    private fun hexDump(data: ByteArray, length: Int): String {
        val max = length.coerceAtMost(64)
        val sb = StringBuilder("[")
        for (i in 0 until max) {
            if (i > 0) sb.append(' ')
            sb.append(String.format(Locale.US, "%02X", data[i]))
        }
        if (length > max) sb.append(" ...")
        sb.append(']')
        return sb.toString()
    }

}
