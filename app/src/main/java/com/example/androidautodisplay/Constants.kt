package com.example.androidautodisplay

object Constants {
    const val ACTION_START = "ro.stf_ftw.libauto.START"
    const val ACTION_STOP = "ro.stf_ftw.libauto.STOP"
    const val ACTION_USB_STATUS = "ro.stf_ftw.libauto.USB_STATUS"
    const val ACTION_WIFI_STATUS = "ro.stf_ftw.libauto.WIFI_STATUS"
    const val ACTION_TRANSPORT_START = "ro.stf_ftw.libauto.TRANSPORT_START"
    const val ACTION_TRANSPORT_STOP = "ro.stf_ftw.libauto.TRANSPORT_STOP"
    const val ACTION_TRANSPORT_STATUS = "ro.stf_ftw.libauto.TRANSPORT_STATUS"
    const val ACTION_TRANSPORT_SEND = "ro.stf_ftw.libauto.TRANSPORT_SEND"
    const val ACTION_TRANSPORT_PING = "ro.stf_ftw.libauto.TRANSPORT_PING"
    const val ACTION_TRANSPORT_LOGS = "ro.stf_ftw.libauto.TRANSPORT_LOGS"
    const val ACTION_TRANSPORT_SAVE_LOGS = "ro.stf_ftw.libauto.TRANSPORT_SAVE_LOGS"
    const val ACTION_USB_MONITOR_START = "ro.stf_ftw.libauto.USB_MONITOR_START"
    const val ACTION_USB_MONITOR_STOP = "ro.stf_ftw.libauto.USB_MONITOR_STOP"
    const val ACTION_USB_SEND_TEST = "ro.stf_ftw.libauto.USB_SEND_TEST"
    const val ACTION_USB_ENABLE_AOAP = "ro.stf_ftw.libauto.USB_ENABLE_AOAP"
    const val ACTION_USB_EXPORT_LOGS = "ro.stf_ftw.libauto.USB_EXPORT_LOGS"
    const val ACTION_USB_EXPORT_LOGCAT = "ro.stf_ftw.libauto.USB_EXPORT_LOGCAT"
    const val ACTION_USB_SHARE_LOGS = "ro.stf_ftw.libauto.USB_SHARE_LOGS"
    const val ACTION_USB_PROBE = "ro.stf_ftw.libauto.USB_PROBE"
    const val ACTION_USB_AA_START = "ro.stf_ftw.libauto.USB_AA_START"
    const val ACTION_USB_SELECT_AND_START = "ro.stf_ftw.libauto.USB_SELECT_AND_START"
    const val ACTION_USB_LOGS = "ro.stf_ftw.libauto.USB_LOGS"
    const val ACTION_USB_EVENT = "ro.stf_ftw.libauto.USB_EVENT"
    const val ACTION_WIRELESS_START = "ro.stf_ftw.libauto.WIRELESS_START"
    const val ACTION_WIRELESS_STOP = "ro.stf_ftw.libauto.WIRELESS_STOP"
    const val ACTION_WIRELESS_STATUS = "ro.stf_ftw.libauto.WIRELESS_STATUS"

    const val EXTRA_STATUS = "status"
    const val EXTRA_HOST = "host"
    const val EXTRA_PORT = "port"
    const val EXTRA_SUCCESS = "success"
    const val EXTRA_LATENCY_MS = "latency_ms"
    const val EXTRA_LOG_PATH = "log_path"
    const val EXTRA_CONNECTIONS = "connections"
    const val EXTRA_BYTES_IN = "bytes_in"
    const val EXTRA_BYTES_OUT = "bytes_out"
    const val EXTRA_USB_DEVICES = "usb_devices"
    const val EXTRA_USB_PERMISSION = "usb_permission"
    const val EXTRA_USB_SELECTION = "usb_selection"
    const val EXTRA_USB_ENDPOINTS = "usb_endpoints"
    const val EXTRA_USB_ACCESSORY = "usb_accessory"
    const val EXTRA_USB_INTERFACE_COUNT = "usb_interface_count"
    const val EXTRA_USB_BULK_IN_COUNT = "usb_bulk_in_count"
    const val EXTRA_USB_BULK_OUT_COUNT = "usb_bulk_out_count"
    const val EXTRA_USB_READY = "usb_ready"
    const val EXTRA_USB_PROBE_RESULTS = "usb_probe_results"
    const val EXTRA_USB_STATE = "usb_state"
    const val EXTRA_USB_LOG_URI = "usb_log_uri"
    const val EXTRA_USB_SHARE = "usb_share"
    const val EXTRA_USB_BYTES_IN = "usb_bytes_in"
    const val EXTRA_USB_BYTES_OUT = "usb_bytes_out"
    const val EXTRA_USB_LAST_ERROR = "usb_last_error"
    const val EXTRA_USB_EVENT = "usb_event"
    const val EXTRA_USB_DEVICE_NAME = "usb_device_name"
    const val EXTRA_USB_PERMISSION_GRANTED = "usb_permission_granted"
    const val EXTRA_USB_LOG_PATH = "usb_log_path"
    const val EXTRA_WIRELESS_DETAILS = "wireless_details"
    const val USB_PERMISSION = "ro.stf_ftw.libauto.USB_PERMISSION"

    const val AOAP_PREFS = "aoap_prefs"
    const val AOAP_MANUFACTURER = "aoap_manufacturer"
    const val AOAP_MODEL = "aoap_model"
    const val AOAP_DESCRIPTION = "aoap_description"
    const val AOAP_VERSION = "aoap_version"
    const val AOAP_URI = "aoap_uri"
    const val AOAP_SERIAL = "aoap_serial"

    const val PROJECTION_PREFS = "projection_prefs"
    const val PROJECTION_RESOLUTION = "projection_resolution"
    const val PROJECTION_NATIVE_ASPECT = "projection_native_aspect"
    const val AUTOCONNECT_SINGLE_DEVICE = "autoconnect_single_device"
    const val PROJECTION_RESOLUTION_480P = "480p"
    const val PROJECTION_RESOLUTION_720P = "720p"
    const val PROJECTION_RESOLUTION_1080P = "1080p"
    const val DEFAULT_PROJECTION_RESOLUTION = PROJECTION_RESOLUTION_720P

    const val DEFAULT_AOAP_MANUFACTURER = "Android"
    const val DEFAULT_AOAP_MODEL = "Android Auto"
    const val DEFAULT_AOAP_DESCRIPTION = "Android Auto"
    const val DEFAULT_AOAP_VERSION = "1.0"
    const val DEFAULT_AOAP_URI = ""
    const val DEFAULT_AOAP_SERIAL = ""

    const val LEGACY_AOAP_MANUFACTURER = "AndroidAutoDisplay"
    const val LEGACY_AOAP_MODEL = "AndroidAutoDisplay"
    const val LEGACY_AOAP_DESCRIPTION = "Android Auto Display"
    const val LEGACY_AOAP_SERIAL = "AAD-0001"

    const val AA_START_DELAY_MS = 1500L

    const val NOTIFICATION_CHANNEL_ID = "projection_channel"
    const val NOTIFICATION_ID = 1001
    const val DEFAULT_TRANSPORT_PORT = 5277

    fun normalizeAoapValue(key: String, value: String?): String {
        return when (key) {
            AOAP_MANUFACTURER -> if (value == null || value == LEGACY_AOAP_MANUFACTURER) {
                DEFAULT_AOAP_MANUFACTURER
            } else {
                value
            }
            AOAP_MODEL -> if (value == null || value == LEGACY_AOAP_MODEL) {
                DEFAULT_AOAP_MODEL
            } else {
                value
            }
            AOAP_DESCRIPTION -> if (value == null || value == LEGACY_AOAP_DESCRIPTION) {
                DEFAULT_AOAP_DESCRIPTION
            } else {
                value
            }
            AOAP_VERSION -> value ?: DEFAULT_AOAP_VERSION
            AOAP_URI -> value ?: DEFAULT_AOAP_URI
            AOAP_SERIAL -> if (value == null || value == LEGACY_AOAP_SERIAL) {
                DEFAULT_AOAP_SERIAL
            } else {
                value
            }
            else -> value ?: ""
        }
    }
}
