package com.sethu.emergency.location

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geographic bounding box representing spatial extent of mapped incidents.
 */
data class GeoBoundingBox(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
    val centerLat: Double,
    val centerLon: Double,
    val spanLat: Double,
    val spanLon: Double
)

/**
 * Pure offline geographic coordinate utility functions.
 * Operates with standard offline mathematical models.
 * Zero external services, zero network calls.
 */
object CoordinateUtils {

    private const val EARTH_RADIUS_METERS = 6_371_000.0
    private const val DEFAULT_MIN_PADDING_DEGREES = 0.005 // ~500 meters padding

    /**
     * Checks if given latitude and longitude represent a valid real-world GPS position.
     */
    fun isValid(latitude: Double?, longitude: Double?): Boolean {
        if (latitude == null || longitude == null) return false
        if (latitude.isNaN() || longitude.isNaN()) return false
        if (latitude.isInfinite() || longitude.isInfinite()) return false
        return latitude in -90.0..90.0 && longitude in -180.0..180.0
    }

    /**
     * Formats latitude and longitude with given decimal precision.
     */
    fun formatCoordinates(latitude: Double?, longitude: Double?, precision: Int = 5): String {
        if (!isValid(latitude, longitude)) return "Unavailable"
        return String.format(Locale.US, "%.${precision}f, %.${precision}f", latitude!!, longitude!!)
    }

    /**
     * Formats accuracy in meters with a plus/minus prefix.
     */
    fun formatAccuracy(accuracyMeters: Float?): String {
        if (accuracyMeters == null || accuracyMeters <= 0f || accuracyMeters.isNaN()) return "Unknown accuracy"
        return "±${accuracyMeters.roundToInt()}m"
    }

    /**
     * Calculates great-circle distance between two GPS points using the Haversine formula.
     * Returns distance in meters.
     */
    fun haversineDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        if (!isValid(lat1, lon1) || !isValid(lat2, lon2)) return 0.0

        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)

        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(rLat1) * cos(rLat2) *
                sin(dLon / 2) * sin(dLon / 2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }

    /**
     * Calculates the initial bearing from point 1 to point 2 in degrees [0.0, 360.0).
     */
    fun initialBearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        if (!isValid(lat1, lon1) || !isValid(lat2, lon2)) return 0.0

        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)

        val y = sin(dLon) * cos(rLat2)
        val x = cos(rLat1) * sin(rLat2) - sin(rLat1) * cos(rLat2) * cos(dLon)

        val initialBearing = Math.toDegrees(atan2(y, x))
        return (initialBearing + 360.0) % 360.0
    }

    /**
     * Formats distance in meters or kilometers for human readability.
     */
    fun formatDistance(distanceMeters: Double): String {
        if (distanceMeters < 0.0 || distanceMeters.isNaN()) return "0 m"
        return if (distanceMeters < 1000.0) {
            "${distanceMeters.roundToInt()} m"
        } else {
            String.format(Locale.US, "%.1f km", distanceMeters / 1000.0)
        }
    }

    /**
     * Calculates bounding box for a collection of coordinate points.
     * Returns null if coordinates list is empty or contains no valid points.
     */
    fun calculateBoundingBox(
        coordinates: List<Pair<Double, Double>>,
        paddingRatio: Double = 0.15
    ): GeoBoundingBox? {
        val validPoints = coordinates.filter { isValid(it.first, it.second) }
        if (validPoints.isEmpty()) return null

        var minLat = validPoints.minOf { it.first }
        var maxLat = validPoints.maxOf { it.first }
        var minLon = validPoints.minOf { it.second }
        var maxLon = validPoints.maxOf { it.second }

        var spanLat = maxLat - minLat
        var spanLon = maxLon - minLon

        if (spanLat < DEFAULT_MIN_PADDING_DEGREES) {
            val delta = (DEFAULT_MIN_PADDING_DEGREES - spanLat) / 2.0
            minLat -= delta
            maxLat += delta
            spanLat = DEFAULT_MIN_PADDING_DEGREES
        } else {
            val pad = spanLat * paddingRatio
            minLat -= pad
            maxLat += pad
            spanLat += (pad * 2)
        }

        if (spanLon < DEFAULT_MIN_PADDING_DEGREES) {
            val delta = (DEFAULT_MIN_PADDING_DEGREES - spanLon) / 2.0
            minLon -= delta
            maxLon += delta
            spanLon = DEFAULT_MIN_PADDING_DEGREES
        } else {
            val pad = spanLon * paddingRatio
            minLon -= pad
            maxLon += pad
            spanLon += (pad * 2)
        }

        // Clamp to valid world range
        minLat = minLat.coerceIn(-90.0, 90.0)
        maxLat = maxLat.coerceIn(-90.0, 90.0)
        minLon = minLon.coerceIn(-180.0, 180.0)
        maxLon = maxLon.coerceIn(-180.0, 180.0)

        val centerLat = (minLat + maxLat) / 2.0
        val centerLon = (minLon + maxLon) / 2.0

        return GeoBoundingBox(
            minLat = minLat,
            maxLat = maxLat,
            minLon = minLon,
            maxLon = maxLon,
            centerLat = centerLat,
            centerLon = centerLon,
            spanLat = (maxLat - minLat).coerceAtLeast(0.0001),
            spanLon = (maxLon - minLon).coerceAtLeast(0.0001)
        )
    }
}
