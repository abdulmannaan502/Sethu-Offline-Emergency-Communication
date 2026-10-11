package com.sethu.emergency.comm.coordinator

import com.sethu.emergency.comm.model.SethuMessage
import com.sethu.emergency.comm.model.SosReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class CoordinatorReportStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: CoordinatorReportStore

    @Before
    fun setup() {
        store = CoordinatorReportStore(tempFolder.root)
    }

    private fun createSampleReport(
        id: String = UUID.randomUUID().toString(),
        category: String = "Fire",
        sender: String = "iQOO 15"
    ): SosReport {
        return SosReport(
            reportId = id,
            category = category,
            peopleCount = "2",
            childrenInvolved = false,
            elderlyInvolved = true,
            medicalEmergency = true,
            notes = "Elderly person needs oxygen assistance",
            latitude = 13.0827,
            longitude = 80.2707,
            accuracyMeters = 3.5f,
            senderDeviceName = sender
        )
    }

    @Test
    fun testSaveReportPreservesCompleteData() {
        val report = createSampleReport()
        val isNew = store.saveReport(report)

        assertTrue(isNew)
        val all = store.getAllReports()
        assertEquals(1, all.size)
        assertEquals(report.reportId, all[0].reportId)
        assertEquals(report.category, all[0].category)
        assertEquals(report.peopleCount, all[0].peopleCount)
        assertEquals(report.childrenInvolved, all[0].childrenInvolved)
        assertEquals(report.elderlyInvolved, all[0].elderlyInvolved)
        assertEquals(report.medicalEmergency, all[0].medicalEmergency)
        assertEquals(report.notes, all[0].notes)
        assertEquals(report.latitude, all[0].latitude)
        assertEquals(report.longitude, all[0].longitude)
        assertEquals(report.senderDeviceName, all[0].senderDeviceName)
    }

    @Test
    fun testDeduplicationByOriginalReportId() {
        val id = UUID.randomUUID().toString()
        val report1 = createSampleReport(id = id)
        val report2 = createSampleReport(id = id)

        val firstSave = store.saveReport(report1)
        val secondSave = store.saveReport(report2)

        assertTrue("First save must succeed as new", firstSave)
        assertFalse("Second save with identical reportId must be flagged as duplicate", secondSave)
        assertEquals("Coordinator store must contain only 1 entry", 1, store.getAllReports().size)
    }

    @Test
    fun testCoordinatorAckMessageFormat() {
        val reportId = UUID.randomUUID().toString()
        val ackMessage = SethuMessage(
            senderDeviceName = "Motorola Coordinator",
            messageType = SethuMessage.TYPE_COORDINATOR_ACK,
            payload = reportId
        )

        val jsonString = ackMessage.toJsonString()
        val parsed = SethuMessage.fromJson(jsonString)

        assertTrue(parsed != null)
        assertEquals(SethuMessage.TYPE_COORDINATOR_ACK, parsed!!.messageType)
        assertEquals(reportId, parsed.payload)
        assertEquals("Motorola Coordinator", parsed.senderDeviceName)

        // Verify ACK is NOT an SOS report and should never be forwarded as an SOS report
        assertTrue(parsed.messageType != SethuMessage.TYPE_SOS_REPORT)
    }

    @Test
    fun testReportIngestionFromMessagePayload() {
        val report = createSampleReport()
        val incomingMessage = SethuMessage(
            messageId = report.reportId,
            senderDeviceName = "Vivo Relay",
            messageType = SethuMessage.TYPE_SOS_REPORT,
            payload = report.toJsonString()
        )

        // Parse and validate
        val parsedReport = SosReport.fromJson(incomingMessage.payload)?.copy(
            senderDeviceName = incomingMessage.senderDeviceName
        )

        assertTrue("Valid JSON payload must parse into SosReport", parsedReport != null)
        val isNew = store.saveReport(parsedReport!!)
        assertTrue("Newly ingested report must be marked new for ACK generation", isNew)
        assertEquals("Sender device name must reflect delivering peer", "Vivo Relay", store.getAllReports().first().senderDeviceName)
    }

    @Test
    fun testCorruptedMessagePayloadRejection() {
        val incomingMessage = SethuMessage(
            messageId = UUID.randomUUID().toString(),
            senderDeviceName = "Unknown Peer",
            messageType = SethuMessage.TYPE_SOS_REPORT,
            payload = "NOT_A_VALID_JSON_STRING"
        )

        val parsedReport = SosReport.fromJson(incomingMessage.payload)
        assertTrue("Corrupted payload must return null", parsedReport == null)
        assertEquals("Store must remain empty when payload is invalid", 0, store.getAllReports().size)
    }

    @Test
    fun testDurableStorageRecognitionOnRetransmission() {
        val report = createSampleReport()
        val isFirstSaveNew = store.saveReport(report)
        assertTrue(isFirstSaveNew)

        // Simulate retransmission of the same report
        val isSecondSaveNew = store.saveReport(report)
        assertFalse("Retransmission must not be flagged as new", isSecondSaveNew)

        val isDurablyStored = isSecondSaveNew || store.getAllReports().any { it.reportId == report.reportId }
        assertTrue("Already persisted report must be recognized as durably stored", isDurablyStored)
    }
}
