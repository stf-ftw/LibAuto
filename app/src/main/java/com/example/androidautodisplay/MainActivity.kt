package com.example.androidautodisplay

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private data class ProjectionResolution(
        val key: String,
        val radioId: Int
    )

    private data class MediaKeyAction(
        val key: String,
        val titleRes: Int,
        val aaScanCode: Int,
        val aaName: String
    )

    private lateinit var launcherContainer: View
    private lateinit var projectionContainer: View
    private lateinit var projectionStatus: TextView
    private lateinit var usbDeviceList: LinearLayout
    private lateinit var projectionResolutionGroup: RadioGroup
    private lateinit var projectionNativeAspectCheckbox: CheckBox
    private lateinit var autoConnectCheckbox: CheckBox
    private lateinit var mediaKeySettingsButton: ImageButton
    private lateinit var mediaKeyMappingSummary: TextView
    private lateinit var mediaKeySettingsOverlay: View
    private lateinit var mediaKeySettingsList: LinearLayout
    private lateinit var mediaKeyLearningStatus: TextView
    private lateinit var mediaKeySettingsClose: Button
    private lateinit var mediaKeyClearAll: Button
    private lateinit var statusText: TextView
    private lateinit var deviceIpText: TextView
    private lateinit var wifiSsidText: TextView
    private lateinit var hotspotText: TextView
    private lateinit var transportStatus: TextView
    private lateinit var transportCounters: TextView
    private lateinit var transportPingStatus: TextView
    private lateinit var transportToggle: Button
    private lateinit var transportSend: Button
    private lateinit var transportPing: Button
    private lateinit var transportSaveLogs: Button
    private lateinit var usbDevicesText: TextView
    private lateinit var usbPermissionText: TextView
    private lateinit var usbSelectedText: TextView
    private lateinit var usbEndpointsText: TextView
    private lateinit var usbAccessoryText: TextView
    private lateinit var usbStateText: TextView
    private lateinit var usbSummaryText: TextView
    private lateinit var usbCountersText: TextView
    private lateinit var usbLogStatusText: TextView
    private lateinit var usbSendTestButton: Button
    private lateinit var usbEnableAoapButton: Button
    private lateinit var usbStartAaButton: Button
    private lateinit var usbExportLogsButton: Button
    private lateinit var usbExportLogcatButton: Button
    private lateinit var usbShareLogsButton: Button
    private lateinit var usbProbeButton: Button
    private lateinit var usbProbeResults: TextView
    private lateinit var wirelessStatusText: TextView
    private lateinit var wirelessStartButton: Button
    private lateinit var wirelessStopButton: Button
    private lateinit var aoapManufacturerInput: EditText
    private lateinit var aoapModelInput: EditText
    private lateinit var aoapDescriptionInput: EditText
    private lateinit var aoapVersionInput: EditText
    private lateinit var aoapUriInput: EditText
    private lateinit var aoapSerialInput: EditText
    private lateinit var aoapSaveButton: Button
    private lateinit var targetIpInput: EditText
    private lateinit var targetPortInput: EditText
    private lateinit var videoViewport: FrameLayout
    private lateinit var videoSurface: SurfaceView
    private lateinit var testVideoButton: Button
    private lateinit var testAudioButton: Button
    private var aasdkRunning = false
    private var projectionStarting = false
    private var statusReceiverRegistered = false
    private var pendingProjectionCloseReason: String? = null
    private var lastLoggedProjectionUsbState: String? = null
    private val projectionCloseHandler = Handler(Looper.getMainLooper())
    private val delayedProjectionClose = Runnable {
        pendingProjectionCloseReason = null
        showLauncherScreen()
    }
    private val wifiMonitor by lazy { WifiMonitor(this) }
    private var transportRunning = false
    private var videoTesting = false
    private var audioTesting = false
    private var pendingAaStartAfterMicPermission = false
    private var pendingAaStartAfterLocationPermission = false
    private var pendingAaStartAfterBluetoothPermission = false
    private var pendingWirelessAaStart = false
    private var pendingSelectedDeviceName: String? = null
    private var autoConnectAttemptedDeviceName: String? = null
    private var projectionVideoWidth = 1280
    private var projectionVideoHeight = 720
    private var projectionFrameWidth = 1280
    private var projectionFrameHeight = 720
    private var projectionMarginWidth = 0
    private var projectionMarginHeight = 0
    private var lastVideoLayoutSignature: String? = null
    private var mediaSession: MediaSession? = null
    private var learningMediaKeyAction: MediaKeyAction? = null
    private var learningMediaKeyDownSignature: String? = null
    private var mediaKeyStatusOverride: String? = null
    private var touchActive = false
    private var lastTouchMoveMs = 0L
    private var lastTouchX = -1
    private var lastTouchY = -1
    private var lastTouchSecondX = -1
    private var lastTouchSecondY = -1
    private var lastTouchPointCount = 0
    private var touchMoveCount = 0
    private var lastTouchMoveSentMs = 0L
    private var touchMoveScheduled = false
    private var pendingTouchMove: PendingTouchMove? = null
    private val touchPointerSlots = mutableMapOf<Int, Int>()
    private val touchSendExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "aa-touch-send").apply { isDaemon = true }
    }
    private val touchSendsInFlight = AtomicInteger(0)
    private var droppedTouchMoveSends = 0L
    private val prefs by lazy { getSharedPreferences("transport_prefs", MODE_PRIVATE) }
    private val aoapPrefs by lazy { getSharedPreferences(Constants.AOAP_PREFS, MODE_PRIVATE) }
    private val projectionPrefs by lazy { getSharedPreferences(Constants.PROJECTION_PREFS, MODE_PRIVATE) }
    private val mediaKeyPrefs by lazy { getSharedPreferences("media_key_mappings", MODE_PRIVATE) }
    private val projectionResolutions by lazy {
        listOf(
            ProjectionResolution(Constants.PROJECTION_RESOLUTION_480P, R.id.projection_resolution_480p),
            ProjectionResolution(Constants.PROJECTION_RESOLUTION_720P, R.id.projection_resolution_720p),
            ProjectionResolution(Constants.PROJECTION_RESOLUTION_1080P, R.id.projection_resolution_1080p)
        )
    }
    private val mediaKeyActions by lazy {
        listOf(
            MediaKeyAction("home", R.string.media_key_home, AA_KEYCODE_HOME, "HOME"),
            MediaKeyAction("back", R.string.media_key_back, AA_KEYCODE_BACK, "BACK"),
            MediaKeyAction("menu", R.string.media_key_menu, AA_KEYCODE_MENU, "MENU"),
            MediaKeyAction("dpad_up", R.string.media_key_dpad_up, AA_KEYCODE_DPAD_UP, "UP"),
            MediaKeyAction("dpad_down", R.string.media_key_dpad_down, AA_KEYCODE_DPAD_DOWN, "DOWN"),
            MediaKeyAction("dpad_left", R.string.media_key_dpad_left, AA_KEYCODE_DPAD_LEFT, "LEFT"),
            MediaKeyAction("dpad_right", R.string.media_key_dpad_right, AA_KEYCODE_DPAD_RIGHT, "RIGHT"),
            MediaKeyAction("dpad_enter", R.string.media_key_dpad_enter, AA_KEYCODE_DPAD_CENTER, "ENTER"),
            MediaKeyAction("previous", R.string.media_key_previous, AA_KEYCODE_MEDIA_PREVIOUS, "PREV"),
            MediaKeyAction("next", R.string.media_key_next, AA_KEYCODE_MEDIA_NEXT, "NEXT"),
            MediaKeyAction("play_pause", R.string.media_key_play_pause, AA_KEYCODE_MEDIA_PLAY_PAUSE, "TOGGLE_PLAY"),
            MediaKeyAction("play", R.string.media_key_play, AA_KEYCODE_MEDIA_PLAY, "PLAY"),
            MediaKeyAction("pause", R.string.media_key_pause, AA_KEYCODE_MEDIA_PAUSE, "PAUSE"),
            MediaKeyAction("stop", R.string.media_key_stop, AA_KEYCODE_MEDIA_STOP, "STOP"),
            MediaKeyAction("rewind", R.string.media_key_rewind, AA_KEYCODE_MEDIA_REWIND, "REWIND"),
            MediaKeyAction("fast_forward", R.string.media_key_fast_forward, AA_KEYCODE_MEDIA_FAST_FORWARD, "FAST_FORWARD"),
            MediaKeyAction("voice", R.string.media_key_voice, AA_KEYCODE_MICROPHONE, "MICROPHONE_1"),
            MediaKeyAction("call", R.string.media_key_call, AA_KEYCODE_CALL, "PHONE"),
            MediaKeyAction("end_call", R.string.media_key_end_call, AA_KEYCODE_ENDCALL, "CALL_END")
        )
    }
    private lateinit var videoSink: VideoSink
    private val audioSink = AudioSink()
    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        MicInputBridge.setPermissionGranted(granted)
        if (granted && pendingSelectedDeviceName != null) {
            continueSelectedDeviceStart()
        } else if (granted && pendingWirelessAaStart) {
            if (ensureLocationPermissionForAaStart() && ensureBluetoothPermissionForAaStart()) {
                startWirelessAaSession()
            }
        } else if (granted && pendingAaStartAfterMicPermission) {
            if (ensureLocationPermissionForAaStart() && ensureBluetoothPermissionForAaStart()) {
                startAaSession()
            }
        } else if (granted) {
            ensureLocationPermission()
        } else if (!granted) {
            android.widget.Toast.makeText(
                this,
                "Microphone permission is required for Android Auto voice input",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
        pendingAaStartAfterMicPermission = false
    }

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            android.widget.Toast.makeText(
                this,
                "Bluetooth permission is needed for Android Auto call pairing",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
        if (pendingAaStartAfterBluetoothPermission) {
            if (pendingSelectedDeviceName != null) {
                continueSelectedDeviceStart()
            } else if (pendingWirelessAaStart) {
                startWirelessAaSession()
            } else {
                startAaSession()
            }
        }
        pendingAaStartAfterBluetoothPermission = false
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (!granted) {
            android.widget.Toast.makeText(
                this,
                "Location permission is needed to feed tablet GPS speed to Android Auto",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
        if (pendingAaStartAfterLocationPermission) {
            if (pendingSelectedDeviceName != null) {
                continueSelectedDeviceStart()
            } else if (pendingWirelessAaStart && ensureBluetoothPermissionForAaStart()) {
                startWirelessAaSession()
            } else if (ensureBluetoothPermissionForAaStart()) {
                startAaSession()
            }
        }
        pendingAaStartAfterLocationPermission = false
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Constants.ACTION_WIFI_STATUS -> {
                    val status = intent.getStringExtra(Constants.EXTRA_STATUS) ?: return
                    statusText.text = if (status == "wifi_connected") {
                        getString(R.string.status_wifi)
                    } else {
                        getString(R.string.status_idle)
                    }
                }
                Constants.ACTION_USB_STATUS -> {
                    val devices = intent.getStringExtra(Constants.EXTRA_USB_DEVICES)
                    val permission = intent.getStringExtra(Constants.EXTRA_USB_PERMISSION)
                    val selection = intent.getStringExtra(Constants.EXTRA_USB_SELECTION)
                    val endpoints = intent.getStringExtra(Constants.EXTRA_USB_ENDPOINTS)
                    val accessory = intent.getStringExtra(Constants.EXTRA_USB_ACCESSORY)
                    val ifaceCount = intent.getIntExtra(Constants.EXTRA_USB_INTERFACE_COUNT, 0)
                    val bulkIn = intent.getIntExtra(Constants.EXTRA_USB_BULK_IN_COUNT, 0)
                    val bulkOut = intent.getIntExtra(Constants.EXTRA_USB_BULK_OUT_COUNT, 0)
                    val ready = intent.getBooleanExtra(Constants.EXTRA_USB_READY, false)
                    val probe = intent.getStringExtra(Constants.EXTRA_USB_PROBE_RESULTS)
                    val state = intent.getStringExtra(Constants.EXTRA_USB_STATE)
                    val bytesIn = intent.getLongExtra(Constants.EXTRA_USB_BYTES_IN, 0)
                    val bytesOut = intent.getLongExtra(Constants.EXTRA_USB_BYTES_OUT, 0)
                    val error = intent.getStringExtra(Constants.EXTRA_USB_LAST_ERROR)
                    if (!devices.isNullOrBlank()) {
                        usbDevicesText.text = "${getString(R.string.usb_devices_label)}: $devices"
                    }
                    if (!permission.isNullOrBlank()) {
                        usbPermissionText.text = "${getString(R.string.usb_permission_label)}: $permission"
                    }
                    if (!selection.isNullOrBlank()) {
                        usbSelectedText.text = "${getString(R.string.usb_selected_label)}: $selection"
                    }
                    if (!endpoints.isNullOrBlank()) {
                        usbEndpointsText.text = "${getString(R.string.usb_endpoints_label)}: $endpoints"
                    }
                    if (!accessory.isNullOrBlank()) {
                        usbAccessoryText.text = "${getString(R.string.usb_accessory_label)}: $accessory"
                    }
                    usbStateText.text = "${getString(R.string.usb_state_label)}: ${formatUsbState(state)}"
                    usbSummaryText.text = "${getString(R.string.usb_interface_count)}: $ifaceCount  " +
                        "${getString(R.string.usb_bulk_in)}: $bulkIn  " +
                        "${getString(R.string.usb_bulk_out)}: $bulkOut"
                    usbCountersText.text = "${getString(R.string.usb_bytes_in)}: $bytesIn  " +
                        "${getString(R.string.usb_bytes_out)}: $bytesOut"
                    usbSendTestButton.isEnabled = false
                    handleProjectionUsbState(state)
                    usbStartAaButton.isEnabled = state == "READY_FOR_AA" && ready && !aasdkRunning
                    usbEnableAoapButton.isEnabled = state == "PRE_AA"
                    if (!probe.isNullOrBlank()) {
                        usbProbeResults.text = "${getString(R.string.usb_probe_results)}: $probe"
                    }
                    if (!error.isNullOrBlank()) {
                        if (!isNoisyUsbError(error)) {
                            usbLogStatusText.text = error
                        }
                    }
                    statusText.text = if (!selection.isNullOrBlank() && selection != "None") {
                        getString(R.string.status_usb)
                    } else {
                        getString(R.string.status_idle)
                    }
                    refreshUsbDeviceList()
                }
                Constants.ACTION_TRANSPORT_STATUS -> {
                    val status = intent.getStringExtra(Constants.EXTRA_STATUS) ?: return
                    val connections = intent.getLongExtra(Constants.EXTRA_CONNECTIONS, 0)
                    val bytesIn = intent.getLongExtra(Constants.EXTRA_BYTES_IN, 0)
                    val bytesOut = intent.getLongExtra(Constants.EXTRA_BYTES_OUT, 0)
                    transportRunning = status == "listening"
                    transportStatus.text = when (status) {
                        "listening" -> getString(R.string.transport_status_listening)
                        "stopped" -> getString(R.string.transport_status_stopped)
                        else -> getString(R.string.transport_status_idle)
                    }
                    transportToggle.setText(
                        if (transportRunning) R.string.transport_stop else R.string.transport_start
                    )
                    transportCounters.text = "${getString(R.string.transport_connections)}: $connections  " +
                        "${getString(R.string.transport_bytes_in)}: $bytesIn  " +
                        "${getString(R.string.transport_bytes_out)}: $bytesOut"
                }
                Constants.ACTION_TRANSPORT_PING -> {
                    val success = intent.getBooleanExtra(Constants.EXTRA_SUCCESS, false)
                    val latency = intent.getLongExtra(Constants.EXTRA_LATENCY_MS, -1)
                    transportPingStatus.text = if (success) {
                        "${getString(R.string.connect_status_ok)} (${latency}ms)"
                    } else {
                        getString(R.string.connect_status_fail)
                    }
                }
                Constants.ACTION_TRANSPORT_LOGS -> {
                    val path = intent.getStringExtra(Constants.EXTRA_LOG_PATH)
                    transportPingStatus.text = if (path.isNullOrBlank()) {
                        getString(R.string.log_save_failed)
                    } else {
                        "${getString(R.string.log_save_prefix)}: $path"
                    }
                }
                Constants.ACTION_USB_LOGS -> {
                    val path = intent.getStringExtra(Constants.EXTRA_USB_LOG_PATH)
                    val uri = intent.getStringExtra(Constants.EXTRA_USB_LOG_URI)
                    val share = intent.getBooleanExtra(Constants.EXTRA_USB_SHARE, false)
                    usbLogStatusText.text = if (path.isNullOrBlank() && uri.isNullOrBlank()) {
                        getString(R.string.log_save_failed)
                    } else {
                        val details = uri ?: path
                        "${getString(R.string.log_save_prefix)}: $details"
                    }
                    val toastText = uri ?: path
                    if (!toastText.isNullOrBlank()) {
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            "Logs saved: $toastText",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                    if (share && !uri.isNullOrBlank()) {
                        shareLogs(uri)
                    }
                }
                Constants.ACTION_WIRELESS_STATUS -> {
                    val status = intent.getStringExtra(Constants.EXTRA_STATUS) ?: "unknown"
                    val details = intent.getStringExtra(Constants.EXTRA_WIRELESS_DETAILS) ?: status
                    wirelessStatusText.text = details
                    if (status == "bluetooth_connected" ||
                        status == "wifi_info_requested" ||
                        status == "wifi_connection_status" ||
                        status == "wifi_start_response"
                    ) {
                        statusText.text = getString(R.string.status_wifi)
                        if (projectionContainer.visibility != View.VISIBLE) {
                            showProjectionScreen()
                            projectionStatus.visibility = View.VISIBLE
                            projectionStatus.text = details
                        }
                    } else if (projectionContainer.visibility == View.VISIBLE && projectionStarting) {
                        projectionStatus.visibility = View.VISIBLE
                        projectionStatus.text = details
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LogFileHelper.appendEvent(this, "MainActivity", "onCreate")
        setContentView(R.layout.activity_main)

        launcherContainer = findViewById(R.id.launcher_container)
        projectionContainer = findViewById(R.id.projection_container)
        projectionStatus = findViewById(R.id.projection_status)
        usbDeviceList = findViewById(R.id.usb_device_list)
        projectionResolutionGroup = findViewById(R.id.projection_resolution_group)
        projectionNativeAspectCheckbox = findViewById(R.id.projection_native_aspect_checkbox)
        autoConnectCheckbox = findViewById(R.id.autoconnect_checkbox)
        mediaKeySettingsButton = findViewById(R.id.media_key_settings_button)
        mediaKeyMappingSummary = findViewById(R.id.media_key_mapping_summary)
        mediaKeySettingsOverlay = findViewById(R.id.media_key_settings_overlay)
        mediaKeySettingsList = findViewById(R.id.media_key_settings_list)
        mediaKeyLearningStatus = findViewById(R.id.media_key_learning_status)
        mediaKeySettingsClose = findViewById(R.id.media_key_settings_close)
        mediaKeyClearAll = findViewById(R.id.media_key_clear_all)
        statusText = findViewById(R.id.status_text)
        deviceIpText = findViewById(R.id.device_ip_text)
        wifiSsidText = findViewById(R.id.wifi_ssid_text)
        hotspotText = findViewById(R.id.hotspot_text)
        transportStatus = findViewById(R.id.transport_status)
        transportCounters = findViewById(R.id.transport_counters)
        transportToggle = findViewById(R.id.transport_toggle_button)
        transportSend = findViewById(R.id.transport_send_button)
        transportPing = findViewById(R.id.transport_ping_button)
        transportSaveLogs = findViewById(R.id.transport_save_logs_button)
        transportPingStatus = findViewById(R.id.transport_ping_status)
        usbDevicesText = findViewById(R.id.usb_devices_text)
        usbPermissionText = findViewById(R.id.usb_permission_text)
        usbSelectedText = findViewById(R.id.usb_selected_text)
        usbEndpointsText = findViewById(R.id.usb_endpoints_text)
        usbAccessoryText = findViewById(R.id.usb_accessory_text)
        usbStateText = findViewById(R.id.usb_state_text)
        usbSummaryText = findViewById(R.id.usb_summary_text)
        usbCountersText = findViewById(R.id.usb_counters_text)
        usbLogStatusText = findViewById(R.id.usb_log_status)
        usbSendTestButton = findViewById(R.id.usb_send_button)
        usbEnableAoapButton = findViewById(R.id.usb_enable_aoap_button)
        usbStartAaButton = findViewById(R.id.usb_start_aa_button)
        usbExportLogsButton = findViewById(R.id.usb_export_logs_button)
        usbExportLogcatButton = findViewById(R.id.usb_export_logcat_button)
        usbShareLogsButton = findViewById(R.id.usb_share_logs_button)
        usbProbeButton = findViewById(R.id.usb_probe_button)
        usbProbeResults = findViewById(R.id.usb_probe_results)
        wirelessStatusText = findViewById(R.id.wireless_status_text)
        wirelessStartButton = findViewById(R.id.wireless_start_button)
        wirelessStopButton = findViewById(R.id.wireless_stop_button)
        aoapManufacturerInput = findViewById(R.id.aoap_manufacturer_input)
        aoapModelInput = findViewById(R.id.aoap_model_input)
        aoapDescriptionInput = findViewById(R.id.aoap_description_input)
        aoapVersionInput = findViewById(R.id.aoap_version_input)
        aoapUriInput = findViewById(R.id.aoap_uri_input)
        aoapSerialInput = findViewById(R.id.aoap_serial_input)
        aoapSaveButton = findViewById(R.id.aoap_save_button)
        targetIpInput = findViewById(R.id.target_ip_input)
        targetPortInput = findViewById(R.id.target_port_input)
        videoViewport = findViewById(R.id.video_viewport)
        videoSurface = findViewById(R.id.video_surface)
        testVideoButton = findViewById(R.id.test_video_button)
        testAudioButton = findViewById(R.id.test_audio_button)
        videoSink = VideoSink(videoSurface)
        AaProjectionSink.bindSurfaceView(videoSurface)
        configureProjectionTouch()
        configureProjectionResolutionPicker()
        configureAutoConnect()
        configureBackButtonHandling()
        configureMediaKeySettings()
        configureMediaSession()
        MicInputBridge.initialize(this)
        BluetoothBridge.initialize(this)
        CarSensorBridge.initialize(this)
        AasdkNative.nativeWarmJvmBindings()
        forwardUsbAttachIntent(intent)
        transportCounters.text = "${getString(R.string.transport_connections)}: 0  " +
            "${getString(R.string.transport_bytes_in)}: 0  " +
            "${getString(R.string.transport_bytes_out)}: 0"
        usbCountersText.text = "${getString(R.string.usb_bytes_in)}: 0  " +
            "${getString(R.string.usb_bytes_out)}: 0"
        usbSummaryText.text = "${getString(R.string.usb_interface_count)}: 0  " +
            "${getString(R.string.usb_bulk_in)}: 0  " +
            "${getString(R.string.usb_bulk_out)}: 0"
        usbSendTestButton.isEnabled = false
        usbStartAaButton.isEnabled = false
        usbStateText.text = "${getString(R.string.usb_state_label)}: ${getString(R.string.usb_state_permission)}"
        usbProbeResults.text = "${getString(R.string.usb_probe_results)}: (none)"

        findViewById<Button>(R.id.start_button).setOnClickListener {
            val mode = if (wifiMonitor.isWifiConnected()) "wifi" else "usb"
            val intent = Intent(this, ProjectionService::class.java).apply {
                action = Constants.ACTION_START
                putExtra("mode", mode)
            }
            ContextCompat.startForegroundService(this, intent)
        }

        findViewById<Button>(R.id.stop_button).setOnClickListener {
            val intent = Intent(this, ProjectionService::class.java).apply {
                action = Constants.ACTION_STOP
            }
            startService(intent)
        }

        transportToggle.setOnClickListener {
            if (transportRunning) {
                startService(Intent(this, ProjectionService::class.java).apply {
                    action = Constants.ACTION_TRANSPORT_STOP
                })
                transportRunning = false
                transportToggle.setText(R.string.transport_start)
            } else {
                val targetPort = getTargetPort()
                val serviceIntent = Intent(this, ProjectionService::class.java).apply {
                    action = Constants.ACTION_TRANSPORT_START
                    putExtra(Constants.EXTRA_PORT, targetPort)
                }
                ContextCompat.startForegroundService(this, serviceIntent)
                transportRunning = true
                transportToggle.setText(R.string.transport_stop)
            }
        }

        transportSend.setOnClickListener {
            val targetHost = getTargetHost()
            val targetPort = getTargetPort()
            startService(Intent(this, ProjectionService::class.java).apply {
                action = Constants.ACTION_TRANSPORT_SEND
                putExtra(Constants.EXTRA_HOST, targetHost)
                putExtra(Constants.EXTRA_PORT, targetPort)
            })
        }

        transportPing.setOnClickListener {
            val targetHost = getTargetHost()
            val targetPort = getTargetPort()
            transportPingStatus.text = getString(R.string.connect_status_idle)
            startService(Intent(this, ProjectionService::class.java).apply {
                action = Constants.ACTION_TRANSPORT_PING
                putExtra(Constants.EXTRA_HOST, targetHost)
                putExtra(Constants.EXTRA_PORT, targetPort)
            })
        }

        transportSaveLogs.setOnClickListener {
            startService(Intent(this, ProjectionService::class.java).apply {
                action = Constants.ACTION_TRANSPORT_SAVE_LOGS
            })
        }

        usbSendTestButton.setOnClickListener {
            startService(Intent(this, ProjectionService::class.java).apply {
                action = Constants.ACTION_USB_SEND_TEST
            })
        }

        usbEnableAoapButton.setOnClickListener {
            startService(Intent(this, ProjectionService::class.java).apply {
                action = Constants.ACTION_USB_ENABLE_AOAP
            })
        }

        usbStartAaButton.setOnClickListener {
            if (ensureMicrophonePermissionForAaStart() &&
                ensureLocationPermissionForAaStart() &&
                ensureBluetoothPermissionForAaStart()
            ) {
                startAaSession()
            }
        }

        usbExportLogsButton.setOnClickListener {
            startService(Intent(this, ProjectionService::class.java).apply {
                action = Constants.ACTION_USB_EXPORT_LOGS
            })
        }

        usbExportLogcatButton.setOnClickListener {
            startService(Intent(this, ProjectionService::class.java).apply {
                action = Constants.ACTION_USB_EXPORT_LOGCAT
            })
        }

        usbShareLogsButton.setOnClickListener {
            startService(Intent(this, ProjectionService::class.java).apply {
                action = Constants.ACTION_USB_SHARE_LOGS
            })
        }

        usbProbeButton.setOnClickListener {
            startService(Intent(this, ProjectionService::class.java).apply {
                action = Constants.ACTION_USB_PROBE
            })
        }

        wirelessStartButton.setOnClickListener {
            pendingWirelessAaStart = true
            if (ensureMicrophonePermissionForAaStart() &&
                ensureLocationPermissionForAaStart() &&
                ensureBluetoothPermissionForAaStart()
            ) {
                startWirelessAaSession()
            }
        }

        wirelessStopButton.setOnClickListener {
            pendingWirelessAaStart = false
            startService(Intent(this, ProjectionService::class.java).apply {
                action = Constants.ACTION_WIRELESS_STOP
            })
            showLauncherScreen()
        }

        aoapSaveButton.setOnClickListener {
            aoapPrefs.edit()
                .putString(Constants.AOAP_MANUFACTURER, aoapManufacturerInput.text.toString())
                .putString(Constants.AOAP_MODEL, aoapModelInput.text.toString())
                .putString(Constants.AOAP_DESCRIPTION, aoapDescriptionInput.text.toString())
                .putString(Constants.AOAP_VERSION, aoapVersionInput.text.toString())
                .putString(Constants.AOAP_URI, aoapUriInput.text.toString())
                .putString(Constants.AOAP_SERIAL, aoapSerialInput.text.toString())
                .apply()
        }

        testVideoButton.setOnClickListener {
            if (videoTesting) {
                videoSink.stop()
                videoTesting = false
            } else {
                videoSink.startTest()
                videoTesting = true
            }
        }

        testAudioButton.setOnClickListener {
            if (audioTesting) {
                audioSink.stop()
                audioTesting = false
            } else {
                audioSink.startTestTone()
                audioTesting = true
            }
        }

        loadAoapInputs()

        val initOk = AasdkNative.nativeInit()
        MicInputBridge.setPermissionGranted(hasMicrophonePermission())
        statusText.text = if (initOk) {
            getString(R.string.device_picker_title)
        } else {
            "AASDK native init failed: ${AasdkNative.nativeGetLastError()}"
        }

        ensureMicrophonePermission()
        if (hasMicrophonePermission()) {
            ensureLocationPermission()
        }
        statusText.setOnClickListener(null)
    }

    override fun onResume() {
        super.onResume()
        LogFileHelper.appendEvent(this, "MainActivity", "onResume")
        MicInputBridge.setPermissionGranted(hasMicrophonePermission())
        updateWifiStatus()
        updateNetworkInfo()
        loadTargetInputs()
        refreshUsbDeviceList()
        ContextCompat.startForegroundService(this, Intent(this, ProjectionService::class.java).apply {
            action = Constants.ACTION_USB_MONITOR_START
        })
        if (!statusReceiverRegistered) {
            registerReceiver(statusReceiver, IntentFilter().apply {
                addAction(Constants.ACTION_USB_STATUS)
                addAction(Constants.ACTION_WIFI_STATUS)
                addAction(Constants.ACTION_TRANSPORT_STATUS)
                addAction(Constants.ACTION_TRANSPORT_PING)
                addAction(Constants.ACTION_TRANSPORT_LOGS)
                addAction(Constants.ACTION_USB_LOGS)
                addAction(Constants.ACTION_WIRELESS_STATUS)
            })
            statusReceiverRegistered = true
        }
        hideSystemUi()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUi()
            if (projectionContainer.visibility == View.VISIBLE) {
                updateVideoSurfaceLayout()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        LogFileHelper.appendEvent(this, "MainActivity", "onPause")
        if (statusReceiverRegistered) {
            try {
                unregisterReceiver(statusReceiver)
            } catch (_: IllegalArgumentException) {
                // Some head units deliver odd pause/resume sequences around USB attach flows.
            }
            statusReceiverRegistered = false
        }
        saveTargetInputs()
    }

    override fun onDestroy() {
        super.onDestroy()
        LogFileHelper.appendEvent(this, "MainActivity", "onDestroy")
        CarSensorBridge.stop()
        videoSink.stop()
        audioSink.stop()
        AaProjectionSink.release()
        mediaSession?.release()
        mediaSession = null
        touchSendExecutor.shutdownNow()
    }

    private fun updateWifiStatus() {
        if (wifiMonitor.isWifiConnected()) {
            val statusIntent = Intent(Constants.ACTION_WIFI_STATUS)
            statusIntent.putExtra(Constants.EXTRA_STATUS, "wifi_connected")
            sendBroadcast(statusIntent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        forwardUsbAttachIntent(intent)
    }

    private fun forwardUsbAttachIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            return
        }
        val device = extractUsbDevice(intent) ?: return
        ContextCompat.startForegroundService(
            this,
            Intent(this, ProjectionService::class.java).apply {
                action = Constants.ACTION_USB_EVENT
                putExtra(Constants.EXTRA_USB_EVENT, UsbManager.ACTION_USB_DEVICE_ATTACHED)
                putExtra(UsbManager.EXTRA_DEVICE, device)
            }
        )
    }

    private fun extractUsbDevice(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    private fun updateNetworkInfo() {
        val ips = NetworkInfoProvider.getDeviceIpv4Addresses()
        val ipText = if (ips.isEmpty()) {
            "${getString(R.string.device_ip_label)}: unknown"
        } else {
            "${getString(R.string.device_ip_label)}: ${ips.joinToString()}"
        }
        deviceIpText.text = ipText

        val ssid = WifiInfoProvider.getWifiSsid(this)
        wifiSsidText.text = "${getString(R.string.wifi_ssid_label)}: $ssid"

        val hotspotInfo = HotspotInfoProvider.getHotspotInfo(this)
        val hotspotLabel = if (hotspotInfo.ssid != null) {
            "${getString(R.string.hotspot_label)}: ${hotspotInfo.ssid}"
        } else {
            "${getString(R.string.hotspot_label)}: ${hotspotInfo.detail}"
        }
        val manualHint = if (hotspotInfo.requiresManualEnable) {
            " ${getString(R.string.hotspot_manual)}"
        } else {
            ""
        }
        hotspotText.text = hotspotLabel + manualHint
    }

    private fun configureProjectionResolutionPicker() {
        val selected = selectedProjectionResolution()
        projectionNativeAspectCheckbox.isChecked = useNativeProjectionAspect()
        projectionResolutionGroup.check(selected.radioId)
        applyProjectionResolution(selected)
        projectionResolutionGroup.setOnCheckedChangeListener { _, checkedId ->
            val resolution = projectionResolutions.firstOrNull { it.radioId == checkedId }
                ?: selectedProjectionResolution()
            projectionPrefs.edit()
                .putString(Constants.PROJECTION_RESOLUTION, resolution.key)
                .apply()
            applyProjectionResolution(resolution)
        }
        projectionNativeAspectCheckbox.setOnCheckedChangeListener { _, isChecked ->
            projectionPrefs.edit()
                .putBoolean(Constants.PROJECTION_NATIVE_ASPECT, isChecked)
                .apply()
            applyProjectionResolution(selectedProjectionResolution())
        }
    }

    private fun selectedProjectionResolution(): ProjectionResolution {
        val storedKey = projectionPrefs.getString(
            Constants.PROJECTION_RESOLUTION,
            Constants.DEFAULT_PROJECTION_RESOLUTION
        )
        val key = if (ProjectionResolutionOptions.isKnownQualityKey(storedKey)) {
            storedKey
        } else {
            Constants.DEFAULT_PROJECTION_RESOLUTION
        }
        return projectionResolutions.firstOrNull { it.key == key }
            ?: projectionResolutions.first { it.key == Constants.DEFAULT_PROJECTION_RESOLUTION }
    }

    private fun useNativeProjectionAspect(): Boolean {
        return projectionPrefs.getBoolean(Constants.PROJECTION_NATIVE_ASPECT, false)
    }

    private fun configureAutoConnect() {
        autoConnectCheckbox.isChecked = useAutoConnectSingleDevice()
        autoConnectCheckbox.setOnCheckedChangeListener { _, isChecked ->
            projectionPrefs.edit()
                .putBoolean(Constants.AUTOCONNECT_SINGLE_DEVICE, isChecked)
                .apply()
            autoConnectAttemptedDeviceName = null
            refreshUsbDeviceList()
        }
    }

    private fun useAutoConnectSingleDevice(): Boolean {
        return projectionPrefs.getBoolean(Constants.AUTOCONNECT_SINGLE_DEVICE, false)
    }

    private fun applyProjectionResolution(resolution: ProjectionResolution) {
        val resolved = ProjectionResolutionOptions.resolve(this, resolution.key, useNativeProjectionAspect())
        projectionVideoWidth = resolved.width
        projectionVideoHeight = resolved.height
        projectionFrameWidth = resolved.frameWidth
        projectionFrameHeight = resolved.frameHeight
        projectionMarginWidth = resolved.marginWidth
        projectionMarginHeight = resolved.marginHeight
        AasdkNative.nativeSetVideoResolution(
            resolved.width,
            resolved.height,
            resolved.frameWidth,
            resolved.frameHeight,
            resolved.marginWidth,
            resolved.marginHeight,
            resolved.nativeCode
        )
        updateVideoSurfaceLayout()
    }

    private fun updateVideoSurfaceLayout() {
        projectionContainer.post {
            val containerWidth = projectionContainer.width
            val containerHeight = projectionContainer.height
            if (containerWidth <= 0 || containerHeight <= 0 ||
                projectionVideoWidth <= 0 || projectionVideoHeight <= 0 ||
                projectionFrameWidth <= 0 || projectionFrameHeight <= 0) {
                return@post
            }
            val videoAspect = projectionVideoWidth.toFloat() / projectionVideoHeight.toFloat()
            val containerAspect = containerWidth.toFloat() / containerHeight.toFloat()
            val (viewportWidth, viewportHeight) = if (containerAspect > videoAspect) {
                val height = containerHeight
                (height * videoAspect).roundToInt().coerceAtLeast(1) to height
            } else {
                val width = containerWidth
                width to (width / videoAspect).roundToInt().coerceAtLeast(1)
            }
            videoViewport.layoutParams = FrameLayout.LayoutParams(
                viewportWidth,
                viewportHeight,
                Gravity.CENTER
            )
            val surfaceWidth = ((viewportWidth.toFloat() * projectionFrameWidth.toFloat()) /
                projectionVideoWidth.toFloat()).roundToInt().coerceAtLeast(1)
            val surfaceHeight = ((viewportHeight.toFloat() * projectionFrameHeight.toFloat()) /
                projectionVideoHeight.toFloat()).roundToInt().coerceAtLeast(1)
            val surfaceLeftMargin = -((surfaceWidth.toFloat() * (projectionMarginWidth.toFloat() / 2f)) /
                projectionFrameWidth.toFloat()).roundToInt()
            val surfaceTopMargin = -((surfaceHeight.toFloat() * (projectionMarginHeight.toFloat() / 2f)) /
                projectionFrameHeight.toFloat()).roundToInt()
            videoSurface.layoutParams = FrameLayout.LayoutParams(
                surfaceWidth,
                surfaceHeight,
                Gravity.TOP or Gravity.START
            ).apply {
                leftMargin = surfaceLeftMargin
                topMargin = surfaceTopMargin
            }
            projectionStatus.bringToFront()
            videoSurface.holder.setFixedSize(projectionFrameWidth, projectionFrameHeight)
            val signature = "container=${containerWidth}x$containerHeight viewport=${viewportWidth}x$viewportHeight " +
                "surface=${surfaceWidth}x$surfaceHeight offset=${surfaceLeftMargin}x$surfaceTopMargin " +
                "holder=${projectionFrameWidth}x$projectionFrameHeight active=${projectionVideoWidth}x$projectionVideoHeight " +
                "margins=${projectionMarginWidth}x$projectionMarginHeight"
            if (signature != lastVideoLayoutSignature) {
                lastVideoLayoutSignature = signature
                AasdkNative.nativeReportProjectionStats("video surface layout $signature")
            }
        }
    }

    private fun refreshUsbDeviceList() {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = manager.deviceList.values.sortedWith(
            compareBy<UsbDevice> { it.vendorId }.thenBy { it.productId }.thenBy { it.deviceName }
        )
        usbDeviceList.removeAllViews()
        if (devices.isEmpty()) {
            autoConnectAttemptedDeviceName = null
            usbDeviceList.addView(TextView(this).apply {
                text = getString(R.string.no_usb_devices)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(16), dp(12), dp(16))
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.libauto_muted))
                textSize = 14f
            })
            return
        }
        for (device in devices) {
            usbDeviceList.addView(createUsbDeviceRow(device, manager.hasPermission(device)))
        }
        maybeAutoConnectSingleDevice(devices)
    }

    private fun maybeAutoConnectSingleDevice(devices: List<UsbDevice>) {
        if (!useAutoConnectSingleDevice()) {
            autoConnectAttemptedDeviceName = null
            return
        }
        if (devices.size != 1) {
            autoConnectAttemptedDeviceName = null
            return
        }
        if (aasdkRunning ||
            projectionContainer.visibility == View.VISIBLE ||
            pendingSelectedDeviceName != null
        ) {
            return
        }
        val device = devices.first()
        if (autoConnectAttemptedDeviceName == device.deviceName) {
            return
        }
        autoConnectAttemptedDeviceName = device.deviceName
        startSelectedDeviceFlow(device.deviceName)
    }

    private fun createUsbDeviceRow(device: UsbDevice, hasPermission: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundResource(R.drawable.device_row_bg)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                startSelectedDeviceFlow(device.deviceName)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }
        row.addView(TextView(this).apply {
            text = "USB"
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.libauto_accent_strong))
            textSize = 11f
            setBackgroundResource(R.drawable.device_icon_bg)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                marginEnd = dp(12)
            }
        })
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@MainActivity).apply {
                text = usbDeviceTitle(device)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.libauto_text))
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
            })
            addView(TextView(this@MainActivity).apply {
                text = usbDeviceMeta(device, hasPermission)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.libauto_dim))
                textSize = 11f
                maxLines = 1
            })
        })
        row.addView(Button(this).apply {
            text = getString(R.string.start_projection)
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(18), 0, dp(18), 0)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.white))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundResource(R.drawable.pill_button)
            setOnClickListener {
                startSelectedDeviceFlow(device.deviceName)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(38)
            ).apply {
                marginStart = dp(12)
            }
        })
        return row
    }

    private fun usbDeviceTitle(device: UsbDevice): String {
        val product = try {
            device.productName
        } catch (_: SecurityException) {
            null
        }
        val manufacturer = try {
            device.manufacturerName
        } catch (_: SecurityException) {
            null
        }
        return listOfNotNull(manufacturer, product)
            .filter { it.isNotBlank() && it != "unknown" }
            .joinToString(" ")
            .ifBlank { "USB device" }
    }

    private fun usbDeviceMeta(device: UsbDevice, hasPermission: Boolean): String {
        val permission = if (hasPermission) "permission granted" else "permission needed"
        val ids = String.format(Locale.US, "vid=%04X pid=%04X", device.vendorId, device.productId)
        return "${device.deviceName} - $ids - $permission"
    }

    private fun startSelectedDeviceFlow(deviceName: String) {
        pendingSelectedDeviceName = deviceName
        LogFileHelper.appendEvent(this, "MainActivity", "startSelectedDeviceFlow device=$deviceName")
        if (ensureMicrophonePermissionForAaStart() &&
            ensureLocationPermissionForAaStart() &&
            ensureBluetoothPermissionForAaStart()
        ) {
            continueSelectedDeviceStart()
        }
    }

    private fun continueSelectedDeviceStart() {
        val deviceName = pendingSelectedDeviceName ?: return
        if (!hasMicrophonePermission()) {
            ensureMicrophonePermissionForAaStart()
            return
        }
        if (!CarSensorBridge.hasLocationPermission(this)) {
            ensureLocationPermissionForAaStart()
            return
        }
        if (!ensureBluetoothPermissionForAaStart()) {
            return
        }
        pendingSelectedDeviceName = null
        cancelPendingProjectionClose()
        projectionStarting = true
        aasdkRunning = false
        LogFileHelper.appendEvent(this, "MainActivity", "continueSelectedDeviceStart device=$deviceName")
        showProjectionScreen()
        projectionStatus.visibility = View.VISIBLE
        projectionStatus.text = getString(R.string.projection_connecting)
        ContextCompat.startForegroundService(this, Intent(this, ProjectionService::class.java).apply {
            action = Constants.ACTION_USB_SELECT_AND_START
            putExtra(Constants.EXTRA_USB_DEVICE_NAME, deviceName)
        })
    }

    private fun showProjectionScreen() {
        cancelPendingProjectionClose()
        if (projectionContainer.visibility != View.VISIBLE) {
            LogFileHelper.appendEvent(this, "MainActivity", "showProjectionScreen")
        }
        launcherContainer.visibility = View.GONE
        mediaKeySettingsButton.visibility = View.GONE
        mediaKeySettingsOverlay.visibility = View.GONE
        projectionContainer.visibility = View.VISIBLE
        videoSurface.visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateVideoSurfaceLayout()
        hideSystemUi()
    }

    private fun showLauncherScreen() {
        if (projectionContainer.visibility != View.VISIBLE) {
            return
        }
        cancelPendingProjectionClose()
        LogFileHelper.appendEvent(this, "MainActivity", "showLauncherScreen")
        aasdkRunning = false
        projectionStarting = false
        touchActive = false
        touchPointerSlots.clear()
        resetTouchMovement()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        videoSurface.visibility = View.GONE
        projectionContainer.visibility = View.GONE
        launcherContainer.visibility = View.VISIBLE
        mediaKeySettingsButton.visibility = View.VISIBLE
        projectionStatus.visibility = View.VISIBLE
        projectionStatus.text = getString(R.string.projection_connecting)
        usbLogStatusText.text = getString(R.string.launcher_footer)
        hideSystemUi()
    }

    private fun handleProjectionUsbState(state: String?) {
        if (!state.isNullOrBlank() && state != lastLoggedProjectionUsbState) {
            lastLoggedProjectionUsbState = state
            LogFileHelper.appendEvent(
                this,
                "MainActivity",
                "usbState=$state projectionStarting=$projectionStarting aasdkRunning=$aasdkRunning"
            )
        }
        when (state) {
            "STARTING_AA",
            "AA_TLS_HANDSHAKE" -> {
                cancelPendingProjectionClose()
                projectionStarting = true
                aasdkRunning = true
                showProjectionScreen()
                projectionStatus.visibility = View.VISIBLE
                projectionStatus.text = if (state == "STARTING_AA") {
                    getString(R.string.projection_connecting)
                } else {
                    formatUsbState(state)
                }
            }
            "AA_SESSION_ACTIVE" -> {
                cancelPendingProjectionClose()
                projectionStarting = false
                aasdkRunning = true
                showProjectionScreen()
                projectionStatus.visibility = View.GONE
            }
            "READY_FOR_AA",
            "PRE_AA",
            "AOAP_NEGOTIATING",
            "WAITING_FOR_AOAP_REENUMERATION" -> {
                if (projectionStarting || aasdkRunning) {
                    showProjectionScreen()
                    projectionStatus.visibility = View.VISIBLE
                    projectionStatus.text = getString(R.string.projection_connecting)
                }
            }
            "DISCONNECTED" -> {
                if (aasdkRunning && !projectionStarting) {
                    resetProjectionPipeline()
                    showLauncherScreen()
                } else {
                    scheduleProjectionClose(state)
                }
            }
            "ERROR" -> {
                if (!projectionStarting && !aasdkRunning) {
                    scheduleProjectionClose(state)
                } else {
                    projectionStatus.visibility = View.VISIBLE
                    projectionStatus.text = formatUsbState(state)
                }
            }
            "IDLE" -> {
                if (!projectionStarting && !aasdkRunning) {
                    scheduleProjectionClose(state)
                }
            }
        }
    }

    private fun scheduleProjectionClose(reason: String) {
        if (projectionContainer.visibility != View.VISIBLE) {
            return
        }
        if (pendingProjectionCloseReason == reason) {
            return
        }
        pendingProjectionCloseReason = reason
        projectionCloseHandler.removeCallbacks(delayedProjectionClose)
        projectionCloseHandler.postDelayed(delayedProjectionClose, 5_000L)
    }

    private fun cancelPendingProjectionClose() {
        pendingProjectionCloseReason = null
        projectionCloseHandler.removeCallbacks(delayedProjectionClose)
    }

    private fun resetProjectionPipeline() {
        videoSink.stop()
        audioSink.stop()
        AaProjectionSink.resetSession()
    }

    private fun isNoisyUsbError(error: String): Boolean {
        return error.startsWith("USB read failed") ||
            error.startsWith("USB write failed") ||
            error == "USB write timeout" ||
            error == "USB write error"
    }

    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        handleMediaKeyLearningEvent(event)?.let { return it }
        val scanCode = aaButtonCodeForKeyEvent(event)
        if (scanCode != null && projectionContainer.visibility == View.VISIBLE) {
            if (sendAaButtonKeyEvent(scanCode, event)) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun configureBackButtonHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (mediaKeySettingsOverlay.visibility == View.VISIBLE) {
                    hideMediaKeySettings()
                    return
                }
                if (projectionContainer.visibility == View.VISIBLE) {
                    sendAaButtonClick(AA_KEYCODE_BACK)
                    return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })
    }

    private fun configureMediaKeySettings() {
        mediaKeySettingsButton.setOnClickListener {
            showMediaKeySettings()
        }
        mediaKeySettingsClose.setOnClickListener {
            hideMediaKeySettings()
        }
        mediaKeyClearAll.setOnClickListener {
            mediaKeyPrefs.edit().clear().apply()
            learningMediaKeyAction = null
            learningMediaKeyDownSignature = null
            mediaKeyStatusOverride = null
            refreshMediaKeySettings()
        }
        refreshMediaKeySettings()
    }

    private fun showMediaKeySettings() {
        mediaKeySettingsOverlay.visibility = View.VISIBLE
        mediaKeySettingsOverlay.bringToFront()
        learningMediaKeyAction = null
        learningMediaKeyDownSignature = null
        mediaKeyStatusOverride = null
        refreshMediaKeySettings()
    }

    private fun hideMediaKeySettings() {
        mediaKeySettingsOverlay.visibility = View.GONE
        learningMediaKeyAction = null
        learningMediaKeyDownSignature = null
        mediaKeyStatusOverride = null
        refreshMediaKeySettings()
    }

    private fun refreshMediaKeySettings() {
        val mappedCount = mediaKeyActions.count { mediaKeyMappingLabel(it) != null }
        mediaKeyMappingSummary.text = if (mappedCount == 0) {
            getString(R.string.media_keys_summary_none)
        } else {
            getString(R.string.media_keys_summary_count, mappedCount)
        }
        mediaKeyLearningStatus.text = learningMediaKeyAction?.let {
            getString(R.string.media_key_settings_waiting, getString(it.titleRes))
        } ?: mediaKeyStatusOverride ?: getString(R.string.media_key_settings_idle)
        mediaKeySettingsList.removeAllViews()
        mediaKeyActions.forEach { action ->
            mediaKeySettingsList.addView(createMediaKeyRow(action))
        }
    }

    private fun createMediaKeyRow(action: MediaKeyAction): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundResource(R.drawable.device_row_bg)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@MainActivity).apply {
                text = getString(action.titleRes)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.libauto_text))
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
            })
            addView(TextView(this@MainActivity).apply {
                val mapping = mediaKeyMappingLabel(action) ?: getString(R.string.media_key_not_mapped)
                text = "$mapping -> ${action.aaName} (${formatAaButtonCode(action.aaScanCode)})"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.libauto_dim))
                textSize = 11f
                maxLines = 1
            })
        })
        row.addView(Button(this).apply {
            text = getString(R.string.media_key_learn)
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(16), 0, dp(16), 0)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.white))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundResource(R.drawable.pill_button)
            setOnClickListener {
                learningMediaKeyAction = action
                learningMediaKeyDownSignature = null
                mediaKeyStatusOverride = null
                refreshMediaKeySettings()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(38)
            ).apply {
                marginStart = dp(10)
            }
        })
        row.addView(Button(this).apply {
            text = getString(R.string.media_key_clear)
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(14), 0, dp(14), 0)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.libauto_text))
            textSize = 13f
            setBackgroundResource(R.drawable.pill_button_secondary)
            setOnClickListener {
                clearMediaKeyMapping(action)
                if (learningMediaKeyAction == action) {
                    learningMediaKeyAction = null
                    learningMediaKeyDownSignature = null
                }
                mediaKeyStatusOverride = null
                refreshMediaKeySettings()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(38)
            ).apply {
                marginStart = dp(8)
            }
        })
        return row
    }

    private fun handleMediaKeyLearningEvent(event: KeyEvent): Boolean? {
        learningMediaKeyDownSignature?.let { signature ->
            if (event.action == KeyEvent.ACTION_UP && mediaKeyEventSignature(event) == signature) {
                learningMediaKeyDownSignature = null
                return true
            }
        }
        val action = learningMediaKeyAction ?: return null
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            saveMediaKeyMapping(action, event)
            learningMediaKeyAction = null
            learningMediaKeyDownSignature = mediaKeyEventSignature(event)
            mediaKeyStatusOverride = getString(
                R.string.media_key_settings_learned,
                getString(action.titleRes),
                describeMediaKeyEvent(event)
            )
            refreshMediaKeySettings()
        }
        return true
    }

    private fun saveMediaKeyMapping(action: MediaKeyAction, event: KeyEvent) {
        val keyCode = event.keyCode
        val scanCode = event.scanCode
        val editor = mediaKeyPrefs.edit()
        mediaKeyActions.forEach { existing ->
            if (existing != action &&
                mediaKeyPrefs.getInt(mediaKeyPref(existing, "keyCode"), Int.MIN_VALUE) == keyCode &&
                mediaKeyPrefs.getInt(mediaKeyPref(existing, "scanCode"), Int.MIN_VALUE) == scanCode
            ) {
                editor.remove(mediaKeyPref(existing, "keyCode"))
                editor.remove(mediaKeyPref(existing, "scanCode"))
                editor.remove(mediaKeyPref(existing, "label"))
            }
        }
        editor
            .putInt(mediaKeyPref(action, "keyCode"), keyCode)
            .putInt(mediaKeyPref(action, "scanCode"), scanCode)
            .putString(mediaKeyPref(action, "label"), describeMediaKeyEvent(event))
            .apply()
        LogFileHelper.appendEvent(
            this,
            "MediaKeys",
            "mapped ${action.key} keyCode=$keyCode scanCode=$scanCode"
        )
    }

    private fun clearMediaKeyMapping(action: MediaKeyAction) {
        mediaKeyPrefs.edit()
            .remove(mediaKeyPref(action, "keyCode"))
            .remove(mediaKeyPref(action, "scanCode"))
            .remove(mediaKeyPref(action, "label"))
            .apply()
    }

    private fun mediaKeyMatches(action: MediaKeyAction, event: KeyEvent): Boolean {
        val storedKeyCode = mediaKeyPrefs.getInt(mediaKeyPref(action, "keyCode"), Int.MIN_VALUE)
        val storedScanCode = mediaKeyPrefs.getInt(mediaKeyPref(action, "scanCode"), Int.MIN_VALUE)
        if (storedKeyCode == Int.MIN_VALUE && storedScanCode == Int.MIN_VALUE) {
            return false
        }
        if (storedKeyCode == event.keyCode && storedScanCode == event.scanCode) {
            return true
        }
        return storedScanCode != Int.MIN_VALUE &&
            storedScanCode != 0 &&
            storedScanCode == event.scanCode
    }

    private fun mediaKeyMappingLabel(action: MediaKeyAction): String? {
        return mediaKeyPrefs.getString(mediaKeyPref(action, "label"), null)
    }

    private fun mediaKeyPref(action: MediaKeyAction, field: String): String {
        return "${action.key}_$field"
    }

    private fun mediaKeyEventSignature(event: KeyEvent): String {
        return "${event.keyCode}:${event.scanCode}:${event.deviceId}"
    }

    private fun describeMediaKeyEvent(event: KeyEvent): String {
        val keyName = KeyEvent.keyCodeToString(event.keyCode)
            .removePrefix("KEYCODE_")
            .lowercase(Locale.US)
            .replace('_', ' ')
        return if (event.scanCode != 0) {
            "$keyName (scan ${event.scanCode})"
        } else {
            keyName
        }
    }

    private fun formatAaButtonCode(scanCode: Int): String {
        return "0x${scanCode.toString(16).uppercase(Locale.US)}"
    }

    private fun configureMediaSession() {
        val session = MediaSession(this, "LibAutoMediaKeys")
        @Suppress("DEPRECATION")
        session.setFlags(
            MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        session.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val event = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                }
                event?.let { handleMediaKeyLearningEvent(it) }?.let { return it }
                val scanCode = event?.let { aaButtonCodeForKeyEvent(it) }
                return if (event != null && scanCode != null) {
                    sendAaButtonKeyEvent(scanCode, event)
                } else {
                    super.onMediaButtonEvent(mediaButtonIntent)
                }
            }

            override fun onPlay() {
                sendAaButtonClick(AA_KEYCODE_MEDIA_PLAY)
            }

            override fun onPause() {
                sendAaButtonClick(AA_KEYCODE_MEDIA_PAUSE)
            }

            override fun onSkipToNext() {
                sendAaButtonClick(AA_KEYCODE_MEDIA_NEXT)
            }

            override fun onSkipToPrevious() {
                sendAaButtonClick(AA_KEYCODE_MEDIA_PREVIOUS)
            }

            override fun onStop() {
                sendAaButtonClick(AA_KEYCODE_MEDIA_STOP)
            }

            override fun onRewind() {
                sendAaButtonClick(AA_KEYCODE_MEDIA_REWIND)
            }

            override fun onFastForward() {
                sendAaButtonClick(AA_KEYCODE_MEDIA_FAST_FORWARD)
            }

        })
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_STOP or
                        PlaybackState.ACTION_REWIND or
                        PlaybackState.ACTION_FAST_FORWARD
                )
                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
        session.isActive = true
        mediaSession = session
    }

    private fun sendAaButtonKeyEvent(scanCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) {
            return false
        }
        if (event.action == KeyEvent.ACTION_UP) {
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) {
            return true
        }
        return sendAaButtonClick(scanCode)
    }

    private fun sendAaButtonClick(scanCode: Int): Boolean {
        val down = AasdkNative.nativeSendButton(scanCode, true)
        val up = AasdkNative.nativeSendButton(scanCode, false)
        return down || up
    }

    private fun aaButtonCodeForKeyEvent(event: KeyEvent): Int? {
        customAaButtonCodeForKeyEvent(event)?.let { return it }
        return defaultAaButtonCodeForKeyCode(event.keyCode)
    }

    private fun defaultAaButtonCodeForKeyCode(keyCode: Int): Int? {
        return when (keyCode) {
            KeyEvent.KEYCODE_HOME -> AA_KEYCODE_HOME
            KeyEvent.KEYCODE_BACK -> AA_KEYCODE_BACK
            KeyEvent.KEYCODE_DPAD_UP -> AA_KEYCODE_DPAD_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> AA_KEYCODE_DPAD_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> AA_KEYCODE_DPAD_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> AA_KEYCODE_DPAD_RIGHT
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> AA_KEYCODE_DPAD_CENTER
            KeyEvent.KEYCODE_MENU -> AA_KEYCODE_MENU
            KeyEvent.KEYCODE_SEARCH,
            KeyEvent.KEYCODE_VOICE_ASSIST -> AA_KEYCODE_MICROPHONE
            KeyEvent.KEYCODE_CALL -> AA_KEYCODE_CALL
            KeyEvent.KEYCODE_ENDCALL -> AA_KEYCODE_ENDCALL
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> AA_KEYCODE_MEDIA_PLAY_PAUSE
            KeyEvent.KEYCODE_MEDIA_PLAY -> AA_KEYCODE_MEDIA_PLAY
            KeyEvent.KEYCODE_MEDIA_PAUSE -> AA_KEYCODE_MEDIA_PAUSE
            KeyEvent.KEYCODE_MEDIA_STOP -> AA_KEYCODE_MEDIA_STOP
            KeyEvent.KEYCODE_MEDIA_REWIND -> AA_KEYCODE_MEDIA_REWIND
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> AA_KEYCODE_MEDIA_FAST_FORWARD
            KeyEvent.KEYCODE_MEDIA_NEXT -> AA_KEYCODE_MEDIA_NEXT
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> AA_KEYCODE_MEDIA_PREVIOUS
            else -> null
        }
    }

    private fun customAaButtonCodeForKeyEvent(event: KeyEvent): Int? {
        return mediaKeyActions.firstOrNull { action ->
            mediaKeyMatches(action, event)
        }?.aaScanCode
    }

    private fun configureProjectionTouch() {
        videoSurface.setOnTouchListener { _, event ->
            sendAaTouch(event)
            true
        }
    }

    private fun sendAaTouch(event: MotionEvent) {
        if (videoSurface.width <= 0 || videoSurface.height <= 0) {
            return
        }
        val actionMasked = event.actionMasked
        val action = when (actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clearPendingTouchMove()
                touchActive = true
                touchPointerSlots.clear()
                touchPointerSlots[event.getPointerId(0)] = 0
                resetTouchMovement()
                touchMoveCount = 0
                0
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!touchActive || touchPointerSlots.size >= 2) {
                    return
                }
                val slot = firstFreeTouchSlot() ?: return
                touchPointerSlots[event.getPointerId(event.actionIndex)] = slot
                MotionEvent.ACTION_POINTER_DOWN
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (!touchActive) {
                    return
                }
                flushPendingTouchMove()
                touchActive = false
                if (actionMasked == MotionEvent.ACTION_CANCEL) {
                    MotionEvent.ACTION_CANCEL
                } else {
                    MotionEvent.ACTION_UP
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (!touchActive) {
                    return
                }
                flushPendingTouchMove()
                MotionEvent.ACTION_POINTER_UP
            }
            MotionEvent.ACTION_MOVE -> {
                if (!touchActive) {
                    return
                }
                2
            }
            else -> return
        }
        val points = collectTouchPoints(event).ifEmpty {
            if (isTouchReleaseAction(action)) lastKnownTouchPoints() else emptyList()
        }
        if (points.isEmpty()) {
            if (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL) {
                touchActive = false
                touchPointerSlots.clear()
                resetTouchMovement()
            }
            return
        }
        val actionSlot = when (actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP -> {
                touchPointerSlots[event.getPointerId(event.actionIndex)] ?: points.first().slot
            }
            else -> points.first().slot
        }
        val actionIndex = points.indexOfFirst { it.slot == actionSlot }.coerceAtLeast(0)
        if (action == MotionEvent.ACTION_MOVE) {
            queueTouchMove(actionIndex, points)
            return
        }
        sendTouchPoints(action, actionIndex, points)
        rememberTouchPoints(points)
        lastTouchMoveSentMs = SystemClock.uptimeMillis()
        touchMoveCount += 1
        if (actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            resetTouchMovement(points)
        }
        when (actionMasked) {
            MotionEvent.ACTION_POINTER_UP -> {
                val pointerId = event.getPointerId(event.actionIndex)
                touchPointerSlots.remove(pointerId)
                touchMoveHandler.removeCallbacks(sendPendingTouchMove)
                touchMoveScheduled = false
                resetTouchMovement(points.filter { it.slot != actionSlot })
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                clearPendingTouchMove()
                touchActive = false
                touchPointerSlots.clear()
                resetTouchMovement()
            }
        }
    }

    private fun sendTouchPoints(action: Int, actionIndex: Int, points: List<TouchPoint>) {
        val p0 = points.getOrNull(0)
        val p1 = points.getOrNull(1)
        queueAaTouchSend(
            action = action,
            actionIndex = actionIndex,
            pointerCount = points.size,
            x0 = p0?.x ?: 0,
            y0 = p0?.y ?: 0,
            pointerId0 = (p0?.slot ?: 0) + 1,
            x1 = p1?.x ?: 0,
            y1 = p1?.y ?: 0,
            pointerId1 = (p1?.slot ?: 1) + 1
        )
    }

    private fun queueAaTouchSend(
        action: Int,
        actionIndex: Int,
        pointerCount: Int,
        x0: Int,
        y0: Int,
        pointerId0: Int,
        x1: Int,
        y1: Int,
        pointerId1: Int
    ) {
        val inFlight = touchSendsInFlight.get()
        if (action == MotionEvent.ACTION_MOVE && inFlight >= MAX_TOUCH_SENDS_IN_FLIGHT) {
            droppedTouchMoveSends += 1
            if (droppedTouchMoveSends <= 3 || droppedTouchMoveSends % 100L == 0L) {
                AasdkNative.nativeReportProjectionStats("touch move dropped inFlight=$inFlight count=$droppedTouchMoveSends")
            }
            return
        }
        touchSendsInFlight.incrementAndGet()
        touchSendExecutor.execute {
            try {
                AasdkNative.nativeSendTouchMulti(
                    action,
                    actionIndex,
                    pointerCount,
                    x0,
                    y0,
                    pointerId0,
                    x1,
                    y1,
                    pointerId1
                )
            } finally {
                touchSendsInFlight.decrementAndGet()
            }
        }
    }

    private data class PendingTouchMove(val actionIndex: Int, val points: List<TouchPoint>)

    private val touchMoveHandler = Handler(Looper.getMainLooper())

    private val sendPendingTouchMove = Runnable {
        touchMoveScheduled = false
        flushPendingTouchMove()
    }

    private fun queueTouchMove(actionIndex: Int, points: List<TouchPoint>) {
        if (shouldDropTouchMove(points)) {
            return
        }
        pendingTouchMove = PendingTouchMove(actionIndex, points)
        if (touchMoveScheduled) {
            return
        }
        val now = SystemClock.uptimeMillis()
        val interval = if (points.size > 1) TOUCH_MULTI_MOVE_INTERVAL_MS else TOUCH_MOVE_INTERVAL_MS
        val delayMs = if (lastTouchMoveSentMs == 0L) {
            0L
        } else {
            (interval - (now - lastTouchMoveSentMs)).coerceAtLeast(0L)
        }
        touchMoveScheduled = true
        touchMoveHandler.postDelayed(sendPendingTouchMove, delayMs)
    }

    private fun flushPendingTouchMove() {
        val pending = pendingTouchMove ?: return
        pendingTouchMove = null
        sendTouchPoints(MotionEvent.ACTION_MOVE, pending.actionIndex, pending.points)
        lastTouchMoveSentMs = SystemClock.uptimeMillis()
        rememberTouchPoints(pending.points)
        touchMoveCount += 1
    }

    private fun clearPendingTouchMove() {
        pendingTouchMove = null
        touchMoveScheduled = false
        touchMoveHandler.removeCallbacks(sendPendingTouchMove)
    }

    private fun isTouchReleaseAction(action: Int): Boolean {
        return when (action) {
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> true
            else -> false
        }
    }

    private data class TouchPoint(val slot: Int, val x: Int, val y: Int)

    private fun firstFreeTouchSlot(): Int? {
        return (0..1).firstOrNull { slot -> touchPointerSlots.values.none { it == slot } }
    }

    private fun collectTouchPoints(event: MotionEvent): List<TouchPoint> {
        return (0 until event.pointerCount).mapNotNull { index ->
            val slot = touchPointerSlots[event.getPointerId(index)] ?: return@mapNotNull null
            if (slot !in 0..1) {
                return@mapNotNull null
            }
            val frameX = (event.getX(index) / videoSurface.width) * projectionFrameWidth.toFloat()
            val frameY = (event.getY(index) / videoSurface.height) * projectionFrameHeight.toFloat()
            val activeX = frameX - (projectionMarginWidth.toFloat() / 2f)
            val activeY = frameY - (projectionMarginHeight.toFloat() / 2f)
            val x = activeX.toInt().coerceIn(0, projectionVideoWidth - 1)
            val y = activeY.toInt().coerceIn(0, projectionVideoHeight - 1)
            TouchPoint(slot, x, y)
        }.sortedBy { it.slot }.take(2)
    }

    private fun shouldDropTouchMove(points: List<TouchPoint>): Boolean {
        val now = SystemClock.uptimeMillis()
        if (isStationaryTouchMove(points)) {
            return true
        }
        lastTouchMoveMs = now
        return false
    }

    private fun isStationaryTouchMove(points: List<TouchPoint>): Boolean {
        if (lastTouchPointCount != points.size || lastTouchX < 0) {
            return false
        }
        val primary = points.firstOrNull { it.slot == 0 } ?: points.firstOrNull()
        val secondary = points.firstOrNull { it.slot == 1 }
        val primaryStationary = primary == null ||
            (kotlin.math.abs(primary.x - lastTouchX) < TOUCH_MOVE_DEAD_ZONE_PX &&
                kotlin.math.abs(primary.y - lastTouchY) < TOUCH_MOVE_DEAD_ZONE_PX)
        val secondaryStationary = secondary == null ||
            (lastTouchSecondX >= 0 &&
                kotlin.math.abs(secondary.x - lastTouchSecondX) < TOUCH_MOVE_DEAD_ZONE_PX &&
                kotlin.math.abs(secondary.y - lastTouchSecondY) < TOUCH_MOVE_DEAD_ZONE_PX)
        return primaryStationary && secondaryStationary
    }

    private fun rememberTouchPoints(points: List<TouchPoint>) {
        val primary = points.firstOrNull { it.slot == 0 } ?: points.firstOrNull()
        val secondary = points.firstOrNull { it.slot == 1 }
        lastTouchX = primary?.x ?: -1
        lastTouchY = primary?.y ?: -1
        lastTouchSecondX = secondary?.x ?: -1
        lastTouchSecondY = secondary?.y ?: -1
        lastTouchPointCount = points.size
    }

    private fun lastKnownTouchPoints(): List<TouchPoint> {
        if (lastTouchX < 0 || lastTouchY < 0) {
            return emptyList()
        }
        val points = mutableListOf(TouchPoint(0, lastTouchX, lastTouchY))
        if (lastTouchPointCount > 1 && lastTouchSecondX >= 0 && lastTouchSecondY >= 0) {
            points += TouchPoint(1, lastTouchSecondX, lastTouchSecondY)
        }
        return points
    }

    private fun resetTouchMovement() {
        lastTouchMoveMs = 0L
        lastTouchMoveSentMs = 0L
        lastTouchX = -1
        lastTouchY = -1
        lastTouchSecondX = -1
        lastTouchSecondY = -1
        lastTouchPointCount = 0
    }

    private fun resetTouchMovement(points: List<TouchPoint>) {
        lastTouchMoveMs = 0L
        lastTouchMoveSentMs = SystemClock.uptimeMillis()
        rememberTouchPoints(points)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).roundToInt()
    }

    private fun getTargetHost(): String {
        val text = targetIpInput.text?.toString()?.trim()
        return if (text.isNullOrBlank()) "127.0.0.1" else text
    }

    private fun getTargetPort(): Int {
        val text = targetPortInput.text?.toString()?.trim()
        val port = text?.toIntOrNull() ?: Constants.DEFAULT_TRANSPORT_PORT
        return if (port in 1..65535) port else Constants.DEFAULT_TRANSPORT_PORT
    }

    private fun saveTargetInputs() {
        prefs.edit()
            .putString("target_host", getTargetHost())
            .putInt("target_port", getTargetPort())
            .apply()
    }

    private fun loadTargetInputs() {
        val host = prefs.getString("target_host", "127.0.0.1") ?: "127.0.0.1"
        val port = prefs.getInt("target_port", Constants.DEFAULT_TRANSPORT_PORT)
        targetIpInput.setText(host)
        targetPortInput.setText(port.toString())
    }

    private fun loadAoapInputs() {
        aoapManufacturerInput.setText(
            Constants.normalizeAoapValue(
                Constants.AOAP_MANUFACTURER,
                aoapPrefs.getString(Constants.AOAP_MANUFACTURER, null)
            )
        )
        aoapModelInput.setText(
            Constants.normalizeAoapValue(
                Constants.AOAP_MODEL,
                aoapPrefs.getString(Constants.AOAP_MODEL, null)
            )
        )
        aoapDescriptionInput.setText(
            Constants.normalizeAoapValue(
                Constants.AOAP_DESCRIPTION,
                aoapPrefs.getString(Constants.AOAP_DESCRIPTION, null)
            )
        )
        aoapVersionInput.setText(
            Constants.normalizeAoapValue(
                Constants.AOAP_VERSION,
                aoapPrefs.getString(Constants.AOAP_VERSION, null)
            )
        )
        aoapUriInput.setText(
            Constants.normalizeAoapValue(
                Constants.AOAP_URI,
                aoapPrefs.getString(Constants.AOAP_URI, null)
            )
        )
        aoapSerialInput.setText(
            Constants.normalizeAoapValue(
                Constants.AOAP_SERIAL,
                aoapPrefs.getString(Constants.AOAP_SERIAL, null)
            )
        )
    }

    private fun shareLogs(uriString: String) {
        val uri = android.net.Uri.parse(uriString)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "LibAuto USB logs")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.usb_share_logs)))
    }

    private fun formatUsbState(state: String?): String {
        return when (state) {
            "PERMISSION_REQUESTED" -> getString(R.string.usb_state_permission)
            "USB_ATTACHED" -> getString(R.string.usb_state_usb_attached)
            "PRE_AA" -> getString(R.string.usb_state_pre_aa)
            "AOAP_NEGOTIATING" -> getString(R.string.usb_state_aoap_negotiating)
            "WAITING_FOR_AOAP_REENUMERATION" -> getString(R.string.usb_state_waiting_reenum)
            "READY_FOR_AA" -> getString(R.string.usb_state_ready_for_aa)
            "STARTING_AA" -> getString(R.string.usb_state_starting_aa)
            "AA_TLS_HANDSHAKE" -> getString(R.string.usb_state_aa_tls)
            "AA_SESSION_ACTIVE" -> getString(R.string.usb_state_aa_active)
            else -> state ?: "Unknown"
        }
    }

    private fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureMicrophonePermission() {
        if (hasMicrophonePermission()) {
            MicInputBridge.setPermissionGranted(true)
            return
        }
        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun ensureMicrophonePermissionForAaStart(): Boolean {
        if (hasMicrophonePermission()) {
            MicInputBridge.setPermissionGranted(true)
            return true
        }
        pendingAaStartAfterMicPermission = true
        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        return false
    }

    private fun ensureLocationPermissionForAaStart(): Boolean {
        if (CarSensorBridge.hasLocationPermission(this)) {
            return true
        }
        pendingAaStartAfterLocationPermission = true
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
        return false
    }

    private fun ensureBluetoothPermissionForAaStart(): Boolean {
        if (!BluetoothBridge.needsBluetoothConnectPermission(this)) {
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            pendingAaStartAfterBluetoothPermission = true
            bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return false
        }
        return true
    }

    private fun ensureLocationPermission() {
        if (CarSensorBridge.hasLocationPermission(this)) {
            return
        }
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun startAaSession() {
        cancelPendingProjectionClose()
        pendingWirelessAaStart = false
        projectionStarting = true
        aasdkRunning = false
        showProjectionScreen()
        projectionStatus.visibility = View.VISIBLE
        projectionStatus.text = getString(R.string.projection_connecting)
        CarSensorBridge.start()
        startService(Intent(this, ProjectionService::class.java).apply {
            action = Constants.ACTION_USB_AA_START
        })
        usbStartAaButton.isEnabled = false
    }

    private fun startWirelessAaSession() {
        cancelPendingProjectionClose()
        pendingWirelessAaStart = false
        projectionStarting = true
        aasdkRunning = false
        wirelessStatusText.text = getString(R.string.projection_connecting)
        CarSensorBridge.start()
        ContextCompat.startForegroundService(this, Intent(this, ProjectionService::class.java).apply {
            action = Constants.ACTION_WIRELESS_START
        })
    }

    private companion object {
        const val AA_KEYCODE_HOME = 3
        const val AA_KEYCODE_BACK = 4
        const val AA_KEYCODE_CALL = 5
        const val AA_KEYCODE_ENDCALL = 6
        const val AA_KEYCODE_DPAD_UP = 19
        const val AA_KEYCODE_DPAD_DOWN = 20
        const val AA_KEYCODE_DPAD_LEFT = 21
        const val AA_KEYCODE_DPAD_RIGHT = 22
        const val AA_KEYCODE_DPAD_CENTER = 23
        const val AA_KEYCODE_MENU = 2
        const val AA_KEYCODE_MICROPHONE = 84
        const val AA_KEYCODE_MEDIA_PLAY_PAUSE = 85
        const val AA_KEYCODE_MEDIA_NEXT = 87
        const val AA_KEYCODE_MEDIA_PREVIOUS = 88
        const val AA_KEYCODE_MEDIA_REWIND = 89
        const val AA_KEYCODE_MEDIA_FAST_FORWARD = 90
        const val AA_KEYCODE_MEDIA_PLAY = 126
        const val AA_KEYCODE_MEDIA_PAUSE = 127
        const val AA_KEYCODE_MEDIA_STOP = 86
        const val TOUCH_MOVE_INTERVAL_MS = 24L
        const val TOUCH_MULTI_MOVE_INTERVAL_MS = 20L
        const val TOUCH_MOVE_DEAD_ZONE_PX = 3
        const val MAX_TOUCH_SENDS_IN_FLIGHT = 3
    }
}
