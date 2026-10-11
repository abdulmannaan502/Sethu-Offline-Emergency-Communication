package com.sethu.emergency.comm.relay

import com.sethu.emergency.comm.model.SosReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class RelayReportStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: RelayReportStore

    @Before
    fun setup() {
        store = RelayReportStore(tempFolder.root)
    }

    private fun createSampleReport(
        id: String = UUID.randomUUID().toString(),
        category: String = "Flood",
        sender: String = "iQOO 15"
    ): SosReport {
        return SosReport(
            reportId = id,
            category = category,
            peopleCount = "3",
            childrenInvolved = true,
            elderlyInvolved = false,
            medicalEmergency = false,
            notes = "Bridge flooded, 3 people trapped including 1 child",
            latitude = 12.9716,
            longitude = 77.5946,
            accuracyMeters = 5.0f,
            senderDeviceName = sender
        )
    }

    @Test
    fun testReportIngestionAndInitialState() {
        val report = createSampleReport()
        val item = store.saveReceivedReport(report)

        assertEquals(report.reportId, item.report.reportId)
        assertEquals(DeliveryState.PENDING_FORWARD, item.deliveryState)
        assertEquals(1, store.getAllReports().size)
    }

    @Test
    fun testDeduplicationByOriginalReportId() {
        val id = UUID.randomUUID().toString()
        val report1 = createSampleReport(id = id, category = "Flood")
        val report2 = createSampleReport(id = id, category = "Trapped") // same ID, different fields

        store.saveReceivedReport(report1)
        store.saveReceivedReport(report2) // duplicate

        val all = store.getAllReports()
        assertEquals("Duplicate report must not create a new entry", 1, all.size)
        assertEquals(id, all[0].report.reportId)
        assertEquals("Flood", all[0].report.category) // Original preserved
    }

    @Test
    fun testForwardingStateTransitions() {
        val report = createSampleReport()
        store.saveReceivedReport(report)

        // 1. Mark forwarded
        store.markForwarded(report.reportId)
        var item = store.getAllReports().find { it.report.reportId == report.reportId }
        assertNotNull(item)
        assertEquals(DeliveryState.FORWARDED_TO_COORDINATOR, item!!.deliveryState)
        assertNotNull(item.forwardedAt)

        // 2. Mark delivered (Coordinator acknowledged)
        store.markDelivered(report.reportId)
        item = store.getAllReports().find { it.report.reportId == report.reportId }
        assertNotNull(item)
        assertEquals(DeliveryState.DELIVERED_TO_COORDINATOR, item!!.deliveryState)
        assertNotNull(item.deliveredAt)

        // 3. Reports marked delivered should not be in pending queue
        val pending = store.getPendingReports()
        assertTrue(pending.none { it.report.reportId == report.reportId })
    }

    @Test
    fun testFailedForwardRetainsReportInQueue() {
        val report = createSampleReport()
        store.saveReceivedReport(report)

        store.markForwardFailed(report.reportId, "Socket timeout")
        val item = store.getAllReports().find { it.report.reportId == report.reportId }
        assertNotNull(item)
        assertEquals(DeliveryState.FORWARD_FAILED, item!!.deliveryState)
        assertEquals("Socket timeout", item.lastError)

        // Failed reports remain available for retry
        val pending = store.getPendingReports()
        assertEquals(1, pending.size)
        assertEquals(report.reportId, pending[0].report.reportId)
    }

    @Test
    fun testDurabilityAcrossStoreReload() {
        val report = createSampleReport()
        store.saveReceivedReport(report)
        store.markForwarded(report.reportId)

        // Simulate app restart by loading new store from same directory
        val reloadedStore = RelayReportStore(tempFolder.root)
        val reports = reloadedStore.getAllReports()

        assertEquals(1, reports.size)
        assertEquals(report.reportId, reports[0].report.reportId)
        assertEquals(DeliveryState.FORWARDED_TO_COORDINATOR, reports[0].deliveryState)
        assertEquals(report.notes, reports[0].report.notes)
    }
}
