package com.sethu.emergency.ui.components

import com.sethu.emergency.comm.coordinator.IncidentPriority
import com.sethu.emergency.comm.coordinator.PriorityClassifier
import com.sethu.emergency.comm.model.SosReport
import com.sethu.emergency.location.CoordinateUtils
import com.sethu.emergency.ui.screens.MapDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OfflineMapIntegrationTest {

    @Test
    fun testMapDisplayModeEnum() {
        assertEquals("BENGALURU_MAP", MapDisplayMode.BENGALURU_MAP.name)
        assertEquals("TACTICAL_RADAR", MapDisplayMode.TACTICAL_RADAR.name)
        assertEquals(2, MapDisplayMode.values().size)
    }

    @Test
    fun testExpectedMapFileNameAndRelativePath() {
        val expectedFileName = "bengaluru.map"
        val mapsSubdir = "maps"
        val dummyBaseDir = File("C:/storage/emulated/0/Android/data/com.sethu.emergency/files")
        val mapsDir = File(dummyBaseDir, mapsSubdir)
        val targetFile = File(mapsDir, expectedFileName)

        assertEquals("bengaluru.map", targetFile.name)
        assertEquals("maps", targetFile.parentFile?.name)
        assertTrue(targetFile.path.endsWith("maps${File.separator}bengaluru.map"))
    }

    @Test
    fun testMissingMapFileCondition() {
        val nonExistentFile = File("C:/non/existent/path/maps/bengaluru.map")
        assertFalse(nonExistentFile.exists())
        assertFalse(nonExistentFile.isFile && nonExistentFile.length() > 0L)
    }

    @Test
    fun testIncidentCoordinatesFilteringForVectorMap() {
        val reports = listOf(
            SosReport(
                category = "Medical",
                peopleCount = "1",
                latitude = 12.9716,
                longitude = 77.5946
            ),
            SosReport(
                category = "Fire",
                peopleCount = "2",
                latitude = null,
                longitude = 77.5946
            ),
            SosReport(
                category = "Trapped",
                peopleCount = "3",
                latitude = 12.9352,
                longitude = 77.6245
            ),
            SosReport(
                category = "Flood",
                peopleCount = "1",
                latitude = 95.0, // Invalid latitude > 90
                longitude = 77.5946
            )
        )

        val validReports = reports.filter { CoordinateUtils.isValid(it.latitude, it.longitude) }
        val missingReports = reports.filter { !CoordinateUtils.isValid(it.latitude, it.longitude) }

        assertEquals(2, validReports.size)
        assertEquals(2, missingReports.size)

        // Validate priorities
        val validEvaluated = validReports.map { it to PriorityClassifier.evaluate(it) }
        assertEquals(2, validEvaluated.size)
        validEvaluated.forEach { (report, eval) ->
            assertNotNull(eval.priority)
            assertTrue(eval.priority in listOf(
                IncidentPriority.CRITICAL,
                IncidentPriority.HIGH,
                IncidentPriority.MEDIUM,
                IncidentPriority.UNKNOWN
            ))
        }
    }

    @Test
    fun testEmptyReportsHandling() {
        val emptyReports = emptyList<SosReport>()
        val validReports = emptyReports.filter { CoordinateUtils.isValid(it.latitude, it.longitude) }
        assertTrue(validReports.isEmpty())

        val boundingBox = CoordinateUtils.calculateBoundingBox(emptyList())
        assertNull(boundingBox)
    }
}
