package com.sethu.emergency.comm.sender

import com.sethu.emergency.comm.model.SosReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class SenderReportStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: SenderReportStore

    @Before
    fun setup() {
        store = SenderReportStore(tempFolder.root)
    }

    private fun createSampleReport(
        id: String = UUID.randomUUID().toString(),
        category: String = "Medical",
        sender: String = "iQOO 15"
    ): SosReport {
        return SosReport(
            reportId = id,
            category = category,
            peopleCount = "1",
            childrenInvolved = false,
            elderlyInvolved = true,
            medicalEmergency = true,
            notes = "Elderly person in need of insulin",
            latitude = 12.9716,
            longitude = 77.5946,
            accuracyMeters = 4.0f,
            senderDeviceName = sender
        )
    }

    @Test
    fun testReportCreationAndInitialPendingState() {
        val report = createSampleReport()
        val item = store.saveReport(report)

        assertEquals(report.reportId, item.report.reportId)
        assertEquals(SenderDeliveryState.PENDING_DELIVERY, item.deliveryState)
        assertEquals(1, store.getAllReports().size)
        assertEquals(1, store.getPendingReports().size)
    }

    @Test
    fun testDeduplicationByReportId() {
        val id = UUID.randomUUID().toString()
        val report1 = createSampleReport(id = id, category = "Medical")
        val report2 = createSampleReport(id = id, category = "Fire")

        val item1 = store.saveReport(report1)
        val item2 = store.saveReport(report2)

        assertEquals("Should return the existing item on duplicate", item1.report.reportId, item2.report.reportId)
        assertEquals("Duplicate report must not create a second entry in store", 1, store.getAllReports().size)
        assertEquals("Medical", store.getAllReports()[0].report.category)
    }

    @Test
    fun testStateTransitionsAndPendingQueue() {
        val report = createSampleReport()
        store.saveReport(report)

        // 1. Mark transmitted
        store.markTransmitted(report.reportId)
        var item = store.getReport(report.reportId)
        assertNotNull(item)
        assertEquals(SenderDeliveryState.TRANSMITTED, item!!.deliveryState)
        assertNotNull(item.transmittedAt)
        // Transmitted but unacked is still pending confirmation
        assertEquals(1, store.getPendingReports().size)

        // 2. Mark acknowledged by Relay
        store.markAcknowledgedByRelay(report.reportId)
        item = store.getReport(report.reportId)
        assertNotNull(item)
        assertEquals(SenderDeliveryState.ACKNOWLEDGED_BY_RELAY, item!!.deliveryState)
        assertNotNull(item.acknowledgedAt)
        // Once acknowledged by relay, no longer pending
        assertEquals(0, store.getPendingReports().size)

        // 3. Mark acknowledged by Coordinator
        store.markAcknowledgedByCoordinator(report.reportId)
        item = store.getReport(report.reportId)
        assertNotNull(item)
        assertEquals(SenderDeliveryState.ACKNOWLEDGED_BY_COORDINATOR, item!!.deliveryState)
    }

    @Test
    fun testTransmissionFailureRetainsReportInPending() {
        val report = createSampleReport()
        store.saveReport(report)

        store.markTransmissionFailed(report.reportId, "Bluetooth socket disconnected")
        val item = store.getReport(report.reportId)
        assertNotNull(item)
        assertEquals(SenderDeliveryState.TRANSMISSION_FAILED, item!!.deliveryState)
        assertEquals("Bluetooth socket disconnected", item.lastError)

        // Failed items remain in the pending queue for retry
        val pending = store.getPendingReports()
        assertEquals(1, pending.size)
        assertEquals(report.reportId, pending[0].report.reportId)
    }

    @Test
    fun testDurablePersistenceSurvivesReload() {
        val report1 = createSampleReport(category = "Trapped")
        val report2 = createSampleReport(category = "Flood")
        store.saveReport(report1)
        store.saveReport(report2)
        store.markAcknowledgedByRelay(report1.reportId)

        // Create new store instance pointing to same directory (simulating app restart)
        val reloadedStore = SenderReportStore(tempFolder.root)
        val reloadedReports = reloadedStore.getAllReports()

        assertEquals(2, reloadedReports.size)
        val item1 = reloadedStore.getReport(report1.reportId)
        val item2 = reloadedStore.getReport(report2.reportId)

        assertNotNull(item1)
        assertNotNull(item2)
        assertEquals(SenderDeliveryState.ACKNOWLEDGED_BY_RELAY, item1!!.deliveryState)
        assertEquals(SenderDeliveryState.PENDING_DELIVERY, item2!!.deliveryState)
        assertEquals(1, reloadedStore.getPendingReports().size)
    }

    @Test
    fun testCorruptedFileRecovery() {
        val storeFile = File(tempFolder.root, "sethu_sender_store.json")
        storeFile.writeText("{ corrupted non-array json !!! }")

        val recoveredStore = SenderReportStore(tempFolder.root)
        // Must handle corrupted file gracefully without throwing exception
        assertTrue(recoveredStore.getAllReports().isEmpty())

        // Must still be able to save new reports
        val report = createSampleReport()
        recoveredStore.saveReport(report)
        assertEquals(1, recoveredStore.getAllReports().size)
    }
}
