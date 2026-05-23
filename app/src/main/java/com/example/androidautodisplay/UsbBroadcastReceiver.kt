package com.example.androidautodisplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat

class UsbBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
        val serviceIntent = Intent(context, ProjectionService::class.java).apply {
            this.action = Constants.ACTION_USB_EVENT
            putExtra(Constants.EXTRA_USB_EVENT, action)
            if (device != null) {
                putExtra(UsbManager.EXTRA_DEVICE, device)
            }
            if (Constants.USB_PERMISSION == action) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                putExtra(Constants.EXTRA_USB_PERMISSION_GRANTED, granted)
            }
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
