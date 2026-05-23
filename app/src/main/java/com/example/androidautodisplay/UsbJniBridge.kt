package com.example.androidautodisplay

object UsbJniBridge {
    @Volatile
    private var controller: UsbIoController? = null

    fun attach(controller: UsbIoController) {
        this.controller = controller
    }

    fun detach() {
        controller = null
    }

    @JvmStatic
    fun usbOpen(vid: Int, pid: Int): Boolean {
        return controller?.openByVidPid(vid, pid) ?: false
    }

    @JvmStatic
    fun usbOpen(deviceName: String): Boolean {
        return controller?.openByDeviceName(deviceName) ?: false
    }

    @JvmStatic
    fun usbRead(buffer: ByteArray, timeoutMs: Int): Int {
        return controller?.readOnce(buffer, timeoutMs) ?: -1
    }

    @JvmStatic
    fun usbWrite(buffer: ByteArray, len: Int, timeoutMs: Int): Int {
        return controller?.write(buffer, len, timeoutMs) ?: -1
    }

    @JvmStatic
    fun usbClose() {
        controller?.close()
    }
}
