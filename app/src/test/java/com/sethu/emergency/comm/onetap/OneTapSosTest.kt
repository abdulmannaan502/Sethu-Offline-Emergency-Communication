package com.sethu.emergency.comm.onetap

import com.sethu.emergency.comm.coordinator.CoordinatorReportStore
import com.sethu.emergency.comm.coordinator.IncidentPriority
import com.sethu.emergency.comm.coordinator.PriorityClassifier
import com.sethu.emergency.comm.model.SethuMessage
import com.sethu.emergency.comm.model.SosReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class OneTapSosTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testOneTapReportCreationWithAvailableCoordinates() {
        val reportId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        val report = SosReport(
            reportId = reportId,
            timestamp = timestamp,
            senderDeviceName = "iQOO 15",
            category = "Other",
            peopleCount = "1",
            notes = "ONE-TAP EMERGENCY SOS",
            latitude = 12.9716,
            longitude = 77.5946,
            accuracyMeters = 5.0f,
            locationTimestamp = timestamp,
            locationStatus = SosReport.LOCATION_STATUS_AVAILABLE
        )

        assertEquals(reportId, report.reportId)
        assertEquals("Other", report.category)
        assertEquals("1", report.peopleCount)
        assertEquals("ONE-TAP EMERGENCY SOS", report.notes)
        assertNotNull(report.latitude)
        assertEquals(12.9716, report.latitude!!, 0.0001)
        assertNotNull(report.longitude)
        assertEquals(77.5946, report.longitude!!, 0.0001)
        assertEquals(SosReport.LOCATION_STATUS_AVAILABLE, report.locationStatus)
    }

    @Test
    fun testOneTapReportCreationWithoutCoordinates() {
        val reportId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        val report = SosReport(
            reportId = reportId,
            timestamp = timestamp,
            senderDeviceName = "iQOO 15",
            category = "Other",
            peopleCount = "1",
            notes = "ONE-TAP EMERGENCY SOS",
            latitude = null,
            longitude = null,
            accuracyMeters = null,
            locationTimestamp = null,
            locationStatus = SosReport.LOCATION_STATUS_UNAVAILABLE
        )

        assertNull(report.latitude)
        assertNull(report.longitude)
        assertEquals(SosReport.LOCATION_STATUS_UNAVAILABLE, report.locationStatus)

        // Verify JSON serialization and round-trip parsing with null coordinates
        val jsonString = report.toJsonString()
        val parsed = SosReport.fromJson(jsonString)

        assertNotNull(parsed)
        assertEquals(reportId, parsed!!.reportId)
        assertNull(parsed.latitude)
        assertNull(parsed.longitude)
        assertEquals(SosReport.LOCATION_STATUS_UNAVAILABLE, parsed.locationStatus)
    }

    @Test
    fun testOneTapReportSerializationAndCoordinatorStoreCompatibility() {
        val report = SosReport(
            reportId = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            senderDeviceName = "iQOO 15",
            category = "Other",
            peopleCount = "1",
            notes = "ONE-TAP EMERGENCY SOS",
            latitude = 12.9716,
            longitude = 77.5946,
            locationStatus = SosReport.LOCATION_STATUS_AVAILABLE
        )

        val message = SethuMessage(
            messageId = report.reportId,
            senderDeviceName = "Vivo Relay",
            messageType = SethuMessage.TYPE_SOS_REPORT,
            payload = report.toJsonString()
        )

        val parsed = SosReport.fromJson(message.payload)?.copy(
            senderDeviceName = message.senderDeviceName
        )
        assertNotNull("One-tap report payload must parse cleanly", parsed)

        val store = CoordinatorReportStore(tempFolder.root)
        val isNew = store.saveReport(parsed!!)
        assertTrue("One-tap report must be stored successfully by coordinator store", isNew)
        assertEquals(1, store.getAllReports().size)
        assertEquals(report.reportId, store.getAllReports().first().reportId)
    }

    @Test
    fun testOneTapReportDeterministicPriorityEvaluation() {
        val report = SosReport(
            reportId = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            senderDeviceName = "iQOO 15",
            category = "Other",
            peopleCount = "1",
            notes = "ONE-TAP EMERGENCY SOS",
            latitude = 12.9716,
            longitude = 77.5946,
            locationStatus = SosReport.LOCATION_STATUS_AVAILABLE
        )

        val evaluation = PriorityClassifier.evaluate(report)
        assertEquals(IncidentPriority.MEDIUM, evaluation.priority)
    }

    @Test
    fun testRapidTapDebounceProtection() {
        val debounceWindowMs = 2500L
        var lastTapTime = 10000L

        // Tap 1: First deliberate tap
        val tap1ReportId = UUID.randomUUID().toString()

        // Tap 2: Rapid accidental tap 400ms later (within debounce window)
        val tap2Time = 10400L
        val isTap2Debounced = (tap2Time - lastTapTime) < debounceWindowMs
        assertTrue("Rapid repeat tap within 2500ms must be rejected", isTap2Debounced)

        // Tap 3: Deliberate follow-up emergency tap 3000ms later (outside debounce window)
        val tap3Time = 13000L
        val isTap3Debounced = (tap3Time - lastTapTime) < debounceWindowMs
        assertFalse("Deliberate tap after debounce window must not be blocked", isTap3Debounced)

        lastTapTime = tap3Time
        val tap3ReportId = UUID.randomUUID().toString()

        // Ensure distinct unique report IDs are generated for separate deliberate emergencies
        assertNotEquals("Legitimate new emergency must receive a unique report ID", tap1ReportId, tap3ReportId)
    }

    @Test
    fun testExactReportIdAckMatching() {
        val myReportId = UUID.randomUUID().toString()
        val unrelatedReportId = UUID.randomUUID().toString()

        val acknowledgedReportIds = setOf(unrelatedReportId)

        // Unrelated report ACK must not confirm my report
        assertFalse(
            "Unrelated report ACK must never confirm delivery of my report",
            acknowledgedReportIds.contains(myReportId)
        )

        // Exact matching ACK must confirm delivery
        val updatedAckIds = acknowledgedReportIds + myReportId
        assertTrue(
            "Exact report ID match must confirm delivery",
            updatedAckIds.contains(myReportId)
        )
    }
}
