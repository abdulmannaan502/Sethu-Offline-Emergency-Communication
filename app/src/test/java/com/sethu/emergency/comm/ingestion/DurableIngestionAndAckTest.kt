package com.sethu.emergency.comm.ingestion

import com.sethu.emergency.comm.coordinator.CoordinatorReportStore
import com.sethu.emergency.comm.model.SethuMessage
import com.sethu.emergency.comm.model.SosReport
import com.sethu.emergency.comm.relay.DeliveryState
import com.sethu.emergency.comm.relay.RelayReportStore
import com.sethu.emergency.comm.sender.SenderDeliveryState
import com.sethu.emergency.comm.sender.SenderReportStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class DurableIngestionAndAckTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var relayFolder: java.io.File
    private lateinit var coordinatorFolder: java.io.File
    private lateinit var senderFolder: java.io.File

    private lateinit var relayStore: RelayReportStore
    private lateinit var coordinatorStore: CoordinatorReportStore
    private lateinit var senderStore: SenderReportStore

    @Before
    fun setup() {
        relayFolder = tempFolder.newFolder("relay")
        coordinatorFolder = tempFolder.newFolder("coordinator")
        senderFolder = tempFolder.newFolder("sender")

        relayStore = RelayReportStore(relayFolder)
        coordinatorStore = CoordinatorReportStore(coordinatorFolder)
        senderStore = SenderReportStore(senderFolder)
    }

    private fun createSampleReport(
        id: String = UUID.randomUUID().toString(),
        category: String = "Trapped"
    ): SosReport {
        return SosReport(
            reportId = id,
            category = category,
            peopleCount = "2",
            childrenInvolved = true,
            elderlyInvolved = false,
            medicalEmergency = false,
            notes = "Trapped on roof",
            latitude = 12.9716,
            longitude = 77.5946,
            senderDeviceName = "Sender Phone"
        )
    }

    @Test
    fun testCorruptedPayloadRejection() {
        val corruptedPayload = "NOT_A_VALID_JSON_STRING"
        val parsedReport = SosReport.fromJson(corruptedPayload)
        assertNull("Corrupted payload must not parse into a valid SosReport", parsedReport)

        // Ensure stores are not polluted by corrupted payloads
        assertTrue(relayStore.getAllReports().isEmpty())
        assertTrue(coordinatorStore.getAllReports().isEmpty())
    }

    @Test
    fun testRelayDurableIngestionBeforeAck() {
        val report = createSampleReport()
        val msg = SethuMessage(
            messageId = report.reportId,
            senderDeviceName = "Sender Phone",
            messageType = SethuMessage.TYPE_SOS_REPORT,
            payload = report.toJsonString()
        )

        // Simulate Relay ingestion logic
        val parsed = SosReport.fromJson(msg.payload)?.copy(senderDeviceName = msg.senderDeviceName)
        assertNotNull(parsed)

        // Step 1: Save to durable store
        relayStore.saveReceivedReport(parsed!!)

        // Step 2: Verify it is durably present on disk BEFORE sending ACK
        val isDurablyStored = relayStore.getAllReports().any { it.report.reportId == report.reportId }
        assertTrue("Report must be durably stored before ACK generation", isDurablyStored)

        // Step 3: ACK is created with the exact report ID
        val ackMessage = SethuMessage(
            senderDeviceName = "Relay Phone",
            messageType = SethuMessage.TYPE_SOS_ACK,
            payload = report.reportId
        )
        assertEquals(report.reportId, ackMessage.payload)
        assertEquals(SethuMessage.TYPE_SOS_ACK, ackMessage.messageType)
    }

    @Test
    fun testCoordinatorDurableIngestionBeforeAck() {
        val report = createSampleReport()
        val msg = SethuMessage(
            messageId = report.reportId,
            senderDeviceName = "Relay Phone",
            messageType = SethuMessage.TYPE_SOS_REPORT,
            payload = report.toJsonString()
        )

        // Simulate Coordinator ingestion logic
        val parsed = SosReport.fromJson(msg.payload)?.copy(senderDeviceName = msg.senderDeviceName)
        assertNotNull(parsed)

        // Step 1: Save to durable coordinator store
        val isNew = coordinatorStore.saveReport(parsed!!)
        assertTrue("New report must return true on save", isNew)

        // Step 2: Confirm durable storage before ACK
        val isDurablyStored = isNew || coordinatorStore.getAllReports().any { it.reportId == report.reportId }
        assertTrue("Coordinator must verify disk presence before ACK", isDurablyStored)

        // Step 3: Coordinator ACK with exact report ID
        val coordAck = SethuMessage(
            senderDeviceName = "Coordinator Phone",
            messageType = SethuMessage.TYPE_COORDINATOR_ACK,
            payload = report.reportId
        )
        assertEquals(report.reportId, coordAck.payload)
        assertEquals(SethuMessage.TYPE_COORDINATOR_ACK, coordAck.messageType)
    }

    @Test
    fun testRetransmissionPreservesDeduplicationAndEnablesAck() {
        val report = createSampleReport()

        // First ingestion at Coordinator
        val firstSave = coordinatorStore.saveReport(report)
        assertTrue(firstSave)
        assertEquals(1, coordinatorStore.getAllReports().size)

        // Simulated retransmission (e.g. sender didn't get ACK and resends)
        val secondSave = coordinatorStore.saveReport(report)
        assertFalse("Duplicate report must not create a duplicate entry", secondSave)
        assertEquals("Total reports count must remain 1", 1, coordinatorStore.getAllReports().size)

        // But it IS durably stored, so ACK can be safely answered
        val isPresent = coordinatorStore.getAllReports().any { it.reportId == report.reportId }
        assertTrue("Retransmission still confirms durable presence for ACK dispatch", isPresent)
    }

    @Test
    fun testSenderOutboxAcknowledgedTransitionsMatchExactId() {
        val reportA = createSampleReport(id = "REPORT-AAA")
        val reportB = createSampleReport(id = "REPORT-BBB")

        senderStore.saveReport(reportA)
        senderStore.saveReport(reportB)

        assertEquals(SenderDeliveryState.PENDING_DELIVERY, senderStore.getReport("REPORT-AAA")!!.deliveryState)
        assertEquals(SenderDeliveryState.PENDING_DELIVERY, senderStore.getReport("REPORT-BBB")!!.deliveryState)

        // Receive Relay ACK for Report A only
        senderStore.markAcknowledgedByRelay("REPORT-AAA")

        assertEquals(SenderDeliveryState.ACKNOWLEDGED_BY_RELAY, senderStore.getReport("REPORT-AAA")!!.deliveryState)
        assertEquals("Report B must NOT be affected by ACK for Report A",
            SenderDeliveryState.PENDING_DELIVERY, senderStore.getReport("REPORT-BBB")!!.deliveryState)

        // Receive Coordinator ACK for Report A
        senderStore.markAcknowledgedByCoordinator("REPORT-AAA")
        assertEquals(SenderDeliveryState.ACKNOWLEDGED_BY_COORDINATOR, senderStore.getReport("REPORT-AAA")!!.deliveryState)
        assertEquals(SenderDeliveryState.PENDING_DELIVERY, senderStore.getReport("REPORT-BBB")!!.deliveryState)
    }

    @Test
    fun testNoPrematureDeliveredStateOnSocketWriteOnly() {
        val report = createSampleReport()
        senderStore.saveReport(report)

        // Socket write succeeded: markTransmitted()
        senderStore.markTransmitted(report.reportId)
        val item = senderStore.getReport(report.reportId)

        assertNotNull(item)
        assertEquals("Socket write only marks TRANSMITTED, never delivered",
            SenderDeliveryState.TRANSMITTED, item!!.deliveryState)
        // Report MUST remain in pending queue until matching ACK arrives
        val pending = senderStore.getPendingReports()
        assertEquals(1, pending.size)
        assertEquals(report.reportId, pending[0].report.reportId)
    }

    @Test
    fun testRelayDuplicateRetransmissionDurableHandling() {
        val report = createSampleReport()

        // Initial save
        val firstItem = relayStore.saveReceivedReport(report)
        assertEquals(DeliveryState.PENDING_FORWARD, firstItem.deliveryState)
        assertEquals(1, relayStore.getAllReports().size)

        // Duplicate delivery (lost ACK scenario)
        val duplicateItem = relayStore.saveReceivedReport(report)
        assertEquals("Store must return existing item on duplicate", firstItem.report.reportId, duplicateItem.report.reportId)
        assertEquals("Total reports in relay store must remain 1", 1, relayStore.getAllReports().size)
    }

    @Test
    fun testCoordinatorIngestionAcrossProcessReload() {
        val report = createSampleReport()
        coordinatorStore.saveReport(report)
        assertEquals(1, coordinatorStore.getAllReports().size)

        // Simulate app kill and reload from same directory
        val reloadedCoordinator = CoordinatorReportStore(coordinatorFolder)
        assertEquals(1, reloadedCoordinator.getAllReports().size)
        assertEquals(report.reportId, reloadedCoordinator.getAllReports()[0].reportId)

        // Duplicate after reload must still be recognized and not duplicated
        val isNew = reloadedCoordinator.saveReport(report)
        assertFalse(isNew)
        assertEquals(1, reloadedCoordinator.getAllReports().size)
    }
}
