package com.example.androidautodisplay

import android.hardware.usb.UsbDevice

interface ProjectionProtocol {
    fun connectUsb(device: UsbDevice)
    fun connectWifi()
    fun stop()
}

class StubProjectionProtocol : ProjectionProtocol {
    override fun connectUsb(device: UsbDevice) {
        // Placeholder for Android Auto projection protocol implementation.
    }

    override fun connectWifi() {
        // Placeholder for Android Auto projection protocol implementation.
    }

    override fun stop() {
        // Placeholder for shutdown/cleanup.
    }
}
