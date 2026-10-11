package com.sethu.emergency.comm.timestamp

import com.sethu.emergency.comm.coordinator.CoordinatorReportStore
import com.sethu.emergency.comm.model.SethuMessage
import com.sethu.emergency.comm.model.SosReport
import com.sethu.emergency.comm.relay.DeliveryState
import com.sethu.emergency.comm.relay.RelayReportStore
import com.sethu.emergency.comm.util.TimeFormatter
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class TimestampConsistencyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testEpochMillisecondsFormattingInLocalTime() {
        val fixedEpochMs = 1791558272316L // specific epoch millis
        val expectedTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(fixedEpochMs))
        val expectedDateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(fixedEpochMs))

        val formattedTime = TimeFormatter.formatTime(fixedEpochMs)
        val formattedDateTime = TimeFormatter.formatDateTime(fixedEpochMs)

        assertEquals("formatTime must match local device time format", expectedTime, formattedTime)
        assertEquals("formatDateTime must match local device dateTime format", expectedDateTime, formattedDateTime)
    }

    @Test
    fun testZeroOrNegativeEpochMillisecondsSafelyHandled() {
        assertEquals("Not recorded", TimeFormatter.formatTime(null))
        assertEquals("Not recorded", TimeFormatter.formatTime(0L))
        assertEquals("Not recorded", TimeFormatter.formatTime(-100L))

        assertEquals("Not recorded", TimeFormatter.formatDateTime(null))
        assertEquals("Not recorded", TimeFormatter.formatDateTime(0L))
        assertEquals("Not recorded", TimeFormatter.formatDateTime(-500L))
    }

    @Test
    fun testOriginalCreationTimestampPreservedThroughSosReportSerialization() {
        val originalTimestamp = 1791558000000L
        val originalId = UUID.randomUUID().toString()

        val report = SosReport(
            reportId = originalId,
            timestamp = originalTimestamp,
            senderDeviceName = "iQOO 15",
            category = "Flood",
            peopleCount = "3",
            notes = "Original report created on iQOO 15"
        )

        val jsonString = report.toJsonString()
        val parsed = SosReport.fromJson(jsonString)

        assertNotNull(parsed)
        assertEquals("Original creation timestamp must be preserved exactly", originalTimestamp, parsed!!.timestamp)
        assertEquals("Original report ID must be preserved exactly", originalId, parsed.reportId)
    }

    @Test
    fun testOriginalCreationTimestampPreservedInSethuMessageTransport() {
        val originalCreationTimestamp = 1791558123456L
        val report = SosReport(
            reportId = "SOS-ORIG-123",
            timestamp = originalCreationTimestamp,
            senderDeviceName = "iQOO 15",
            category = "Fire",
            peopleCount = "2"
        )

        // Simulating BluetoothRfcommTransport.sendSosReport packaging
        val transportMessage = SethuMessage(
            messageId = report.reportId,
            senderDeviceName = report.senderDeviceName,
            timestamp = report.timestamp,
            messageType = SethuMessage.TYPE_SOS_REPORT,
            payload = report.toJsonString()
        )

        val wireJson = transportMessage.toJsonString()
        val receivedMessage = SethuMessage.fromJson(wireJson)

        assertNotNull(receivedMessage)
        assertEquals("Transport message must preserve original SOS timestamp", originalCreationTimestamp, receivedMessage!!.timestamp)

        val unmarshalledReport = SosReport.fromJson(receivedMessage.payload)
        assertNotNull(unmarshalledReport)
        assertEquals("Unmarshalled report payload must retain original creation timestamp", originalCreationTimestamp, unmarshalledReport!!.timestamp)
    }

    @Test
    fun testRelayStorePreservesCreationTimestampAndSeparatesReceiptTime() {
        val relayStore = RelayReportStore(tempFolder.root)

        val originalCreationTime = System.currentTimeMillis() - 300_000L // Created 5 minutes ago
        val report = SosReport(
            reportId = "RELAY-TEST-1",
            timestamp = originalCreationTime,
            senderDeviceName = "iQOO 15",
            category = "Medical",
            peopleCount = "1"
        )

        // Relay receives the report now
        val relayItem = relayStore.saveReceivedReport(report)

        assertEquals("Relay item report must preserve original creation timestamp", originalCreationTime, relayItem.report.timestamp)
        assertTrue("Relay receipt time must be newer than original creation time", relayItem.receivedAt >= originalCreationTime)
        assertNotEquals("Creation time and receipt time must be distinct", originalCreationTime, relayItem.receivedAt)

        // Forward to coordinator
        relayStore.markForwarded(report.reportId)
        val forwardedItem = relayStore.getAllReports().first { it.report.reportId == report.reportId }
        assertNotNull("Forwarded timestamp must be recorded", forwardedItem.forwardedAt)
        assertEquals("Report original creation timestamp remains untouched after forwarding", originalCreationTime, forwardedItem.report.timestamp)

        // Coordinator delivers ACK
        relayStore.markDelivered(report.reportId)
        val deliveredItem = relayStore.getAllReports().first { it.report.reportId == report.reportId }
        assertNotNull("Delivered timestamp must be recorded", deliveredItem.deliveredAt)
        assertEquals(DeliveryState.DELIVERED_TO_COORDINATOR, deliveredItem.deliveryState)
        assertEquals("Original creation timestamp must still remain intact", originalCreationTime, deliveredItem.report.timestamp)
    }

    @Test
    fun testCoordinatorStoreTracksReceiptTimeSeparatelyFromCreationTime() {
        val coordinatorStore = CoordinatorReportStore(tempFolder.root)

        val originalCreationTime = 1791557000000L
        val coordinatorReceiptTime = 1791560000000L // Arrives at coordinator hours later

        val report = SosReport(
            reportId = "COORD-TEST-1",
            timestamp = originalCreationTime,
            senderDeviceName = "iQOO 15",
            category = "Trapped",
            peopleCount = "4"
        )

        coordinatorStore.saveReport(report, receivedAt = coordinatorReceiptTime)

        val savedReport = coordinatorStore.getAllReports().first { it.reportId == report.reportId }
        assertEquals("Original creation timestamp must be strictly preserved", originalCreationTime, savedReport.timestamp)

        val recordedReceiptTime = coordinatorStore.getReceivedAt(report.reportId)
        assertEquals("Coordinator receipt time must be recorded separately", coordinatorReceiptTime, recordedReceiptTime)
    }

    @Test
    fun testCoordinatorStoreBackwardCompatibilityWithLegacyFormat() {
        val storageDir = tempFolder.root
        val storeFile = File(storageDir, "sethu_coordinator_store.json")

        val legacyReport = SosReport(
            reportId = "LEGACY-1",
            timestamp = 1791556000000L,
            senderDeviceName = "LegacySender",
            category = "Other",
            peopleCount = "1"
        )

        // Legacy format was a JSONArray of serialized JSON strings
        val legacyArr = JSONArray()
        legacyArr.put(legacyReport.toJsonString())
        storeFile.writeText(legacyArr.toString())

        // Load with updated store
        val coordinatorStore = CoordinatorReportStore(storageDir)
        val reports = coordinatorStore.getAllReports()

        assertEquals("Must load legacy report without error", 1, reports.size)
        assertEquals("LEGACY-1", reports[0].reportId)
        assertEquals(1791556000000L, reports[0].timestamp)
    }
}
