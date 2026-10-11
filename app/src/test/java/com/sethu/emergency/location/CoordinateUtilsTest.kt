package com.sethu.emergency.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateUtilsTest {

    @Test
    fun testCoordinateValidation() {
        // Valid coordinates
        assertTrue(CoordinateUtils.isValid(12.9716, 77.5946)) // Bangalore
        assertTrue(CoordinateUtils.isValid(0.0, 0.0))
        assertTrue(CoordinateUtils.isValid(-90.0, -180.0))
        assertTrue(CoordinateUtils.isValid(90.0, 180.0))

        // Invalid coordinates
        assertFalse(CoordinateUtils.isValid(null, 77.5946))
        assertFalse(CoordinateUtils.isValid(12.9716, null))
        assertFalse(CoordinateUtils.isValid(null, null))
        assertFalse(CoordinateUtils.isValid(91.0, 77.5946)) // Lat > 90
        assertFalse(CoordinateUtils.isValid(-90.1, 77.5946)) // Lat < -90
        assertFalse(CoordinateUtils.isValid(12.9716, 180.1)) // Lon > 180
        assertFalse(CoordinateUtils.isValid(12.9716, -180.1)) // Lon < -180
        assertFalse(CoordinateUtils.isValid(Double.NaN, 77.5946))
        assertFalse(CoordinateUtils.isValid(12.9716, Double.POSITIVE_INFINITY))
    }

    @Test
    fun testCoordinateFormatting() {
        assertEquals("12.97160, 77.59460", CoordinateUtils.formatCoordinates(12.9716, 77.5946, 5))
        assertEquals("12.97, 77.59", CoordinateUtils.formatCoordinates(12.9716, 77.5946, 2))
        assertEquals("Unavailable", CoordinateUtils.formatCoordinates(null, 77.5946))
        assertEquals("Unavailable", CoordinateUtils.formatCoordinates(12.9716, null))
        assertEquals("Unavailable", CoordinateUtils.formatCoordinates(95.0, 77.5946))
    }

    @Test
    fun testAccuracyFormatting() {
        assertEquals("±5m", CoordinateUtils.formatAccuracy(5.2f))
        assertEquals("±12m", CoordinateUtils.formatAccuracy(12.0f))
        assertEquals("Unknown accuracy", CoordinateUtils.formatAccuracy(null))
        assertEquals("Unknown accuracy", CoordinateUtils.formatAccuracy(0f))
        assertEquals("Unknown accuracy", CoordinateUtils.formatAccuracy(-3f))
        assertEquals("Unknown accuracy", CoordinateUtils.formatAccuracy(Float.NaN))
    }

    @Test
    fun testHaversineDistanceCalculations() {
        // Same point distance must be 0
        val distZero = CoordinateUtils.haversineDistanceMeters(12.9716, 77.5946, 12.9716, 77.5946)
        assertEquals(0.0, distZero, 0.001)

        // Bangalore (12.9716, 77.5946) to Chennai (13.0827, 80.2707) is ~290 km (approx 290,000m)
        val distBangaloreChennai = CoordinateUtils.haversineDistanceMeters(12.9716, 77.5946, 13.0827, 80.2707)
        assertTrue("Distance should be approx 290 km, actual: $distBangaloreChennai", distBangaloreChennai in 280_000.0..300_000.0)

        // Invalid inputs return 0.0
        assertEquals(0.0, CoordinateUtils.haversineDistanceMeters(99.0, 0.0, 10.0, 10.0), 0.001)
    }

    @Test
    fun testInitialBearing() {
        // Directly North: from (0, 0) to (1, 0) should be 0° (or very close)
        val bearingNorth = CoordinateUtils.initialBearingDegrees(0.0, 0.0, 1.0, 0.0)
        assertEquals(0.0, bearingNorth, 0.1)

        // Directly East: from (0, 0) to (0, 1) should be 90°
        val bearingEast = CoordinateUtils.initialBearingDegrees(0.0, 0.0, 0.0, 1.0)
        assertEquals(90.0, bearingEast, 0.1)

        // Directly South: from (1, 0) to (0, 0) should be 180°
        val bearingSouth = CoordinateUtils.initialBearingDegrees(1.0, 0.0, 0.0, 0.0)
        assertEquals(180.0, bearingSouth, 0.1)

        // Directly West: from (0, 1) to (0, 0) should be 270°
        val bearingWest = CoordinateUtils.initialBearingDegrees(0.0, 1.0, 0.0, 0.0)
        assertEquals(270.0, bearingWest, 0.1)
    }

    @Test
    fun testFormatDistance() {
        assertEquals("450 m", CoordinateUtils.formatDistance(450.0))
        assertEquals("999 m", CoordinateUtils.formatDistance(999.0))
        assertEquals("1.0 km", CoordinateUtils.formatDistance(1000.0))
        assertEquals("2.5 km", CoordinateUtils.formatDistance(2500.0))
        assertEquals("0 m", CoordinateUtils.formatDistance(-5.0))
    }

    @Test
    fun testBoundingBoxCalculations() {
        // Empty list returns null
        assertNull(CoordinateUtils.calculateBoundingBox(emptyList()))

        // List with only invalid points returns null
        assertNull(CoordinateUtils.calculateBoundingBox(listOf(Pair(999.0, 999.0))))

        // Single point returns a padded box centered around that point
        val singleBox = CoordinateUtils.calculateBoundingBox(listOf(Pair(12.9716, 77.5946)))
        assertNotNull(singleBox)
        assertEquals(12.9716, singleBox!!.centerLat, 0.001)
        assertEquals(77.5946, singleBox.centerLon, 0.001)
        assertTrue(singleBox.spanLat > 0.0)
        assertTrue(singleBox.spanLon > 0.0)

        // Multiple points
        val multiPoints = listOf(
            Pair(12.9700, 77.5900),
            Pair(12.9800, 77.6000),
            Pair(12.9750, 77.5950)
        )
        val multiBox = CoordinateUtils.calculateBoundingBox(multiPoints)
        assertNotNull(multiBox)
        assertTrue(multiBox!!.minLat <= 12.9700)
        assertTrue(multiBox.maxLat >= 12.9800)
        assertTrue(multiBox.minLon <= 77.5900)
        assertTrue(multiBox.maxLon >= 77.6000)
    }
}
