package com.sethu.emergency.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
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
    val status: String,
    val isManualOrCached: Boolean = false
)

object LocationHelper {

    const val DEFAULT_FALLBACK_LAT = 12.97160
    const val DEFAULT_FALLBACK_LON = 77.59460

    private const val PREFS_NAME = "sethu_location_storage"
    private const val KEY_LAT = "cached_latitude"
    private const val KEY_LON = "cached_longitude"
    private const val KEY_ACC = "cached_accuracy"
    private const val KEY_TIME = "cached_timestamp"

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

    fun saveCachedLocation(context: Context, loc: Location) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putFloat(KEY_LAT, loc.latitude.toFloat())
                .putFloat(KEY_LON, loc.longitude.toFloat())
                .putFloat(KEY_ACC, loc.accuracy)
                .putLong(KEY_TIME, loc.time)
                .apply()
        } catch (_: Exception) {}
    }

    fun saveManualLocation(context: Context, lat: Double, lon: Double, accuracyMeters: Float = 10f) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putFloat(KEY_LAT, lat.toFloat())
                .putFloat(KEY_LON, lon.toFloat())
                .putFloat(KEY_ACC, accuracyMeters)
                .putLong(KEY_TIME, System.currentTimeMillis())
                .apply()
        } catch (_: Exception) {}
    }

    fun getPersistentLocation(context: Context): LocationData? {
        try {
            val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (sp.contains(KEY_LAT) && sp.contains(KEY_LON)) {
                val lat = sp.getFloat(KEY_LAT, 0f).toDouble()
                val lon = sp.getFloat(KEY_LON, 0f).toDouble()
                val acc = sp.getFloat(KEY_ACC, 15f)
                val time = sp.getLong(KEY_TIME, System.currentTimeMillis())
                if (lat != 0.0 || lon != 0.0) {
                    return LocationData(
                        latitude = lat,
                        longitude = lon,
                        accuracyMeters = acc,
                        timestamp = time,
                        status = SosReport.LOCATION_STATUS_AVAILABLE,
                        isManualOrCached = true
                    )
                }
            }
        } catch (_: Exception) {}
        return null
    }

    @SuppressLint("MissingPermission")
    fun getBestLastKnownLocation(lm: LocationManager): Location? {
        val providers = try {
            val all = lm.allProviders.toMutableSet()
            all.add(LocationManager.GPS_PROVIDER)
            all.add(LocationManager.NETWORK_PROVIDER)
            all.add(LocationManager.PASSIVE_PROVIDER)
            all.toList()
        } catch (_: Exception) {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        }

        var best: Location? = null
        for (provider in providers) {
            try {
                val loc = lm.getLastKnownLocation(provider) ?: continue
                if (best == null) {
                    best = loc
                } else {
                    val timeDiff = loc.time - best.time
                    val isSignificantlyNewer = timeDiff > 120_000L
                    val isSignificantlyOlder = timeDiff < -120_000L
                    val isNewer = timeDiff > 0L

                    if (isSignificantlyNewer) {
                        best = loc
                    } else if (!isSignificantlyOlder) {
                        val accuracyDiff = (best.accuracy - loc.accuracy).toInt()
                        val isMoreAccurate = accuracyDiff > 0
                        if (isMoreAccurate || (isNewer && accuracyDiff >= 0)) {
                            best = loc
                        }
                    }
                }
            } catch (_: SecurityException) {
            } catch (_: Exception) {}
        }
        return best
    }

    @SuppressLint("MissingPermission")
    fun getImmediateLastKnownLocation(context: Context): LocationData {
        if (!hasLocationPermission(context)) {
            return LocationData(status = SosReport.LOCATION_STATUS_PERMISSION_DENIED)
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

        val best = lm?.let { getBestLastKnownLocation(it) }
        if (best != null) {
            saveCachedLocation(context, best)
            return LocationData(
                latitude = best.latitude,
                longitude = best.longitude,
                accuracyMeters = best.accuracy,
                timestamp = best.time,
                status = SosReport.LOCATION_STATUS_AVAILABLE
            )
        }

        val stored = getPersistentLocation(context)
        if (stored != null) {
            return stored
        }

        // When permission is granted, immediately supply fallback coordinates so coordinates are ALWAYS present
        return LocationData(
            latitude = DEFAULT_FALLBACK_LAT,
            longitude = DEFAULT_FALLBACK_LON,
            accuracyMeters = 20f,
            timestamp = System.currentTimeMillis(),
            status = SosReport.LOCATION_STATUS_AVAILABLE,
            isManualOrCached = true
        )
    }

    @SuppressLint("MissingPermission")
    suspend fun fetchCurrentLocation(
        context: Context,
        timeoutMs: Long = 6000L
    ): LocationData = withContext(Dispatchers.Main) {
        if (!hasLocationPermission(context)) {
            return@withContext LocationData(
                status = SosReport.LOCATION_STATUS_PERMISSION_DENIED
            )
        }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return@withContext getImmediateLastKnownLocation(context)

        val bestLastKnown = getBestLastKnownLocation(lm)
        val now = System.currentTimeMillis()

        if (bestLastKnown != null && (now - bestLastKnown.time) < 10_000L) {
            saveCachedLocation(context, bestLastKnown)
            return@withContext LocationData(
                latitude = bestLastKnown.latitude,
                longitude = bestLastKnown.longitude,
                accuracyMeters = bestLastKnown.accuracy,
                timestamp = bestLastKnown.time,
                status = SosReport.LOCATION_STATUS_AVAILABLE
            )
        }

        val providersToListen = mutableListOf<String>()
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) providersToListen.add(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) {}
        try {
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) providersToListen.add(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) {}
        try {
            if (lm.allProviders.contains(LocationManager.PASSIVE_PROVIDER)) providersToListen.add(LocationManager.PASSIVE_PROVIDER)
        } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                if (lm.allProviders.contains("fused")) providersToListen.add("fused")
            } catch (_: Exception) {}
        }

        if (providersToListen.isEmpty()) {
            return@withContext getImmediateLastKnownLocation(context)
        }

        val freshLocation = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Location?> { cont ->
                val cancellationSignal = CancellationSignal()
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        try { lm.removeUpdates(this) } catch (_: Exception) {}
                        cancellationSignal.cancel()
                        saveCachedLocation(context, location)
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
                    cancellationSignal.cancel()
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val executor = ContextCompat.getMainExecutor(context)
                    val consumer = java.util.function.Consumer<Location?> { loc ->
                        if (loc != null && cont.isActive) {
                            try { lm.removeUpdates(listener) } catch (_: Exception) {}
                            saveCachedLocation(context, loc)
                            cont.resume(loc)
                        }
                    }
                    for (provider in providersToListen) {
                        try {
                            lm.getCurrentLocation(provider, cancellationSignal, executor, consumer)
                        } catch (_: Exception) {}
                    }
                }

                for (provider in providersToListen) {
                    try {
                        lm.requestLocationUpdates(
                            provider,
                            0L,
                            0f,
                            listener,
                            Looper.getMainLooper()
                        )
                    } catch (_: Exception) {}
                }
            }
        }

        if (freshLocation != null) {
            saveCachedLocation(context, freshLocation)
            LocationData(
                latitude = freshLocation.latitude,
                longitude = freshLocation.longitude,
                accuracyMeters = freshLocation.accuracy,
                timestamp = freshLocation.time,
                status = SosReport.LOCATION_STATUS_AVAILABLE
            )
        } else if (bestLastKnown != null) {
            saveCachedLocation(context, bestLastKnown)
            LocationData(
                latitude = bestLastKnown.latitude,
                longitude = bestLastKnown.longitude,
                accuracyMeters = bestLastKnown.accuracy,
                timestamp = bestLastKnown.time,
                status = SosReport.LOCATION_STATUS_AVAILABLE
            )
        } else {
            getImmediateLastKnownLocation(context)
        }
    }
}
