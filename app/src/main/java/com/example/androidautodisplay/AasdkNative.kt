package com.example.androidautodisplay

object AasdkNative {
    init {
        System.loadLibrary("aasdk_android")
    }

    external fun nativeInit(): Boolean
    external fun nativeWarmJvmBindings(): Boolean
    external fun nativeStart(): Boolean
    external fun nativeStartAaOverUsb(): Boolean
    external fun nativeSetLogPath(path: String)
    external fun nativeSetVideoResolution(
        width: Int,
        height: Int,
        frameWidth: Int,
        frameHeight: Int,
        marginWidth: Int,
        marginHeight: Int,
        resolutionCode: Int
    )
    external fun nativeSetMicrophonePermission(granted: Boolean)
    external fun nativeReportProjectionStats(message: String)
    external fun nativeOnCarSpeed(speedMetersPerSecond: Float): Boolean
    external fun nativeSendTouch(action: Int, x: Int, y: Int, pointerId: Int): Boolean
    external fun nativeSendButton(scanCode: Int, pressed: Boolean): Boolean
    external fun nativeSendTouchMulti(
        action: Int,
        actionIndex: Int,
        pointerCount: Int,
        x0: Int,
        y0: Int,
        pointerId0: Int,
        x1: Int,
        y1: Int,
        pointerId1: Int
    ): Boolean
    external fun nativeOnMicrophoneFrame(data: ByteArray, ptsUs: Long): Boolean
    external fun nativeStopAaSession()
    external fun nativeStop()
    external fun nativeGetLastError(): String
}
