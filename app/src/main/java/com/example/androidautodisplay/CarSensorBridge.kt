package com.example.androidautodisplay

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max

object CarSensorBridge {
    private const val UPDATE_INTERVAL_MS = 1000L
    private const val MIN_DISTANCE_M = 0f
    private const val ZERO_SPEED_REFRESH_MS = 5000L
    private const val MOVING_SPEED_REFRESH_MS = 1000L
    private const val SPEED_CHANGE_THRESHOLD_MPS = 0.5f

    private lateinit var appContext: Context
    private val started = AtomicBoolean(false)
    private val latestSpeedMps = AtomicReference(0f)
    private val handler = Handler(Looper.getMainLooper())
    private var lastSentSpeedMps = Float.NaN
    private var lastSentAtMs = 0L

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val speed = if (location.hasSpeed()) max(0f, location.speed) else 0f
            latestSpeedMps.set(speed)
            sendSpeedIfNeeded(speed, force = false)
        }

        @Deprecated("Deprecated in Android framework")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    private val periodicSender = object : Runnable {
        override fun run() {
            if (!started.get()) {
                return
            }
            sendSpeedIfNeeded(latestSpeedMps.get(), force = false)
            handler.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun hasLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!::appContext.isInitialized || !hasLocationPermission(appContext)) {
            AasdkNative.nativeOnCarSpeed(0f)
            return
        }
        if (!started.compareAndSet(false, true)) {
            return
        }

        val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        providers.forEach { provider ->
            try {
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.getLastKnownLocation(provider)?.let { locationListener.onLocationChanged(it) }
                    locationManager.requestLocationUpdates(
                        provider,
                        UPDATE_INTERVAL_MS,
                        MIN_DISTANCE_M,
                        locationListener,
                        Looper.getMainLooper()
                    )
                }
            } catch (_: IllegalArgumentException) {
                // Provider absent on this device.
            } catch (_: SecurityException) {
                // Permission changed while starting; keep the 1 Hz zero-speed fallback.
            }
        }
        lastSentSpeedMps = Float.NaN
        lastSentAtMs = 0L
        handler.post(periodicSender)
    }

    fun stop() {
        if (!started.getAndSet(false) || !::appContext.isInitialized) {
            return
        }
        handler.removeCallbacks(periodicSender)
        val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager.removeUpdates(locationListener)
        latestSpeedMps.set(0f)
        AasdkNative.nativeOnCarSpeed(0f)
    }

    private fun sendSpeedIfNeeded(speedMps: Float, force: Boolean) {
        val now = android.os.SystemClock.elapsedRealtime()
        val refreshMs = if (speedMps > SPEED_CHANGE_THRESHOLD_MPS) {
            MOVING_SPEED_REFRESH_MS
        } else {
            ZERO_SPEED_REFRESH_MS
        }
        val changed = lastSentSpeedMps.isNaN() ||
            abs(speedMps - lastSentSpeedMps) >= SPEED_CHANGE_THRESHOLD_MPS
        if (!force && !changed && now - lastSentAtMs < refreshMs) {
            return
        }
        if (AasdkNative.nativeOnCarSpeed(speedMps)) {
            lastSentSpeedMps = speedMps
            lastSentAtMs = now
        }
    }
}
