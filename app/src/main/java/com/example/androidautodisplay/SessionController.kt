package com.example.androidautodisplay

import android.content.Context
import android.hardware.usb.UsbDevice
import android.util.Log

class SessionController(
    private val context: Context,
    private val protocol: ProjectionProtocol = StubProjectionProtocol()
) {
    private var running = false

    fun startUsb(device: UsbDevice) {
        if (running) return
        running = true
        Log.i("SessionController", "Starting USB session with ${device.deviceName}")
        protocol.connectUsb(device)
    }

    fun startWifi() {
        if (running) return
        running = true
        Log.i("SessionController", "Starting Wi-Fi session")
        protocol.connectWifi()
    }

    fun stop() {
        if (!running) return
        running = false
        Log.i("SessionController", "Stopping session")
        protocol.stop()
    }
}
