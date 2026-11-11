package com.unspektrawesome.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.unspektrawesome.capture.CaptureLocation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class CameraLocationController(context: Context,
    private val locationEnabled: () -> Boolean = { context.getSystemService(LocationManager::class.java).isLocationEnabled },
) : AutoCloseable {
    private val context = context.applicationContext
    private val manager = context.getSystemService(LocationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val mutableStatus = MutableStateFlow("Off")
    val status = mutableStatus.asStateFlow()
    @Volatile private var fix: Location? = null
    @Volatile private var enabled = false
    @Volatile private var visible = false
    private var subscription: List<String> = emptyList()
    @Volatile private var permissionKey = 0
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!enabled || !visible || permissionKey != permissions()) return
            if (CaptureLocation.isFresh(location.elapsedRealtimeNanos, SystemClock.elapsedRealtimeNanos())) {
                val previous = fix
                if (previous == null || location.elapsedRealtimeNanos >= previous.elapsedRealtimeNanos) fix = Location(location)
            }
            publishStatus()
        }
        override fun onProviderEnabled(provider: String) { refresh() }
        override fun onProviderDisabled(provider: String) { refresh() }
    }
    private val refreshStatus = object : Runnable {
        override fun run() {
            if (enabled && visible) {
                configure(enabled, visible)
                publishStatus()
                handler.postDelayed(this, 5_000)
            }
        }
    }

    fun hasPermission() = permissions() != 0

    private fun permissions(): Int = when {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED -> 2
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED -> 1
        else -> 0
    }

    @SuppressLint("MissingPermission")
    fun configure(enabled: Boolean, visible: Boolean) {
        val access = permissions()
        val changed = this.enabled != enabled || this.visible != visible || permissionKey != access
        this.enabled = enabled
        this.visible = visible
        if (!changed) return
        manager.removeUpdates(listener)
        handler.removeCallbacks(refreshStatus)
        fix = null
        subscription = emptyList()
        permissionKey = access
        if (enabled && visible && access != 0) {
            val providers = buildList {
                if (access == 2) add(LocationManager.GPS_PROVIDER)
                add(LocationManager.NETWORK_PROVIDER)
            }.filter { it in manager.allProviders }
            subscription = providers.filter { provider ->
                runCatching {
                    manager.requestLocationUpdates(provider, 5_000L, 0f, listener, Looper.getMainLooper())
                    manager.getLastKnownLocation(provider)?.let(listener::onLocationChanged)
                }.isSuccess
            }
            handler.postDelayed(refreshStatus, 5_000)
        }
        publishStatus()
    }

    private fun refresh() {
        fix = null
        publishStatus()
    }

    fun snapshot(): CaptureLocation? {
        if (!enabled || !visible || permissions() != permissionKey || permissionKey == 0 || !locationEnabled()) return null
        val location = fix ?: return null
        if (!CaptureLocation.isFresh(location.elapsedRealtimeNanos, SystemClock.elapsedRealtimeNanos())) return null
        return runCatching {
            CaptureLocation(location.latitude, location.longitude, location.time,
                location.altitude.takeIf { location.hasAltitude() && it.isFinite() },
                location.accuracy.takeIf { location.hasAccuracy() && it.isFinite() && it >= 0 })
        }.getOrNull()
    }

    private fun publishStatus() {
        mutableStatus.value = when {
            !enabled -> "Off"
            !hasPermission() -> "Permission needed"
            !visible -> "Enabled"
            !locationEnabled() -> "Location is off on this device"
            snapshot() != null -> if (permissionKey == 1) "Ready · approximate" else "Ready · precise"
            subscription.isEmpty() -> "Location unavailable"
            else -> "Finding location"
        }
    }

    override fun close() { configure(false, false) }
}
