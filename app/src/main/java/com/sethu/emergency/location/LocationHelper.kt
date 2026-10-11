package com.sethu.emergency.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.sethu.emergency.comm.model.SosReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class LocationData(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMeters: Float? = null,
    val timestamp: Long? = null,
    val status: String
)

object LocationHelper {

    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun isGpsEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    /**
     * Instantly returns the best available last known location without suspending or blocking.
     * Never delays emergency transmission waiting for a fresh satellite lock.
     */
    @SuppressLint("MissingPermission")
    fun getImmediateLastKnownLocation(context: Context): LocationData {
        if (!hasLocationPermission(context)) {
            return LocationData(status = SosReport.LOCATION_STATUS_PERMISSION_DENIED)
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return LocationData(status = SosReport.LOCATION_STATUS_UNAVAILABLE)

        val isGpsOn = try { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) } catch (_: Exception) { false }
        val isNetworkOn = try { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } catch (_: Exception) { false }

        if (!isGpsOn && !isNetworkOn) {
            return LocationData(status = SosReport.LOCATION_STATUS_UNAVAILABLE)
        }

        var best: Location? = null
        if (isGpsOn) {
            try {
                best = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            } catch (_: SecurityException) {}
        }
        if (best == null && isNetworkOn) {
            try {
                best = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } catch (_: SecurityException) {}
        }

        return if (best != null) {
            LocationData(
                latitude = best.latitude,
                longitude = best.longitude,
                accuracyMeters = best.accuracy,
                timestamp = best.time,
                status = SosReport.LOCATION_STATUS_AVAILABLE
            )
        } else {
            LocationData(status = SosReport.LOCATION_STATUS_UNAVAILABLE)
        }
    }

    /**
     * Safely attempts to obtain real device coordinates without blocking indefinitely.
     * Never invents coordinates or makes cloud/geocoding calls.
     */
    @SuppressLint("MissingPermission")
    suspend fun fetchCurrentLocation(
        context: Context,
        timeoutMs: Long = 4500L
    ): LocationData = withContext(Dispatchers.Main) {
        if (!hasLocationPermission(context)) {
            return@withContext LocationData(
                status = SosReport.LOCATION_STATUS_PERMISSION_DENIED
            )
        }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return@withContext LocationData(
                status = SosReport.LOCATION_STATUS_UNAVAILABLE
            )

        val isGpsOn = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val isNetworkOn = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        if (!isGpsOn && !isNetworkOn) {
            return@withContext LocationData(
                status = SosReport.LOCATION_STATUS_UNAVAILABLE
            )
        }

        // Check recent last known location (< 60s old)
        var bestLastKnown: Location? = null
        if (isGpsOn) {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { loc ->
                bestLastKnown = loc
            }
        }
        if (bestLastKnown == null && isNetworkOn) {
            lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let { loc ->
                bestLastKnown = loc
            }
        }

        val now = System.currentTimeMillis()
        if (bestLastKnown != null && (now - bestLastKnown!!.time) < 60_000L) {
            return@withContext LocationData(
                latitude = bestLastKnown!!.latitude,
                longitude = bestLastKnown!!.longitude,
                accuracyMeters = bestLastKnown!!.accuracy,
                timestamp = bestLastKnown!!.time,
                status = SosReport.LOCATION_STATUS_AVAILABLE
            )
        }

        // Request a fresh location fix with timeout
        val freshLocation = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Location?> { cont ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        try { lm.removeUpdates(this) } catch (_: Exception) {}
                        if (cont.isActive) {
                            cont.resume(location)
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }

                cont.invokeOnCancellation {
                    try { lm.removeUpdates(listener) } catch (_: Exception) {}
                }

                try {
                    val provider = when {
                        isGpsOn -> LocationManager.GPS_PROVIDER
                        else -> LocationManager.NETWORK_PROVIDER
                    }
                    lm.requestLocationUpdates(
                        provider,
                        0L,
                        0f,
                        listener,
                        Looper.getMainLooper()
                    )
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

        if (freshLocation != null) {
            LocationData(
                latitude = freshLocation.latitude,
                longitude = freshLocation.longitude,
                accuracyMeters = freshLocation.accuracy,
                timestamp = freshLocation.time,
                status = SosReport.LOCATION_STATUS_AVAILABLE
            )
        } else if (bestLastKnown != null) {
            // Use last known if fresh timed out, recording its actual age/timestamp honestly
            LocationData(
                latitude = bestLastKnown!!.latitude,
                longitude = bestLastKnown!!.longitude,
                accuracyMeters = bestLastKnown!!.accuracy,
                timestamp = bestLastKnown!!.time,
                status = SosReport.LOCATION_STATUS_AVAILABLE
            )
        } else {
            LocationData(
                status = SosReport.LOCATION_STATUS_TIMED_OUT
            )
        }
    }
}
