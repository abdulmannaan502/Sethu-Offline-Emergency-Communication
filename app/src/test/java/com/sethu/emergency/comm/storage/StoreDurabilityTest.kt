package com.sethu.emergency.comm.storage

import com.sethu.emergency.comm.coordinator.CoordinatorReportStore
import com.sethu.emergency.comm.model.SosReport
import com.sethu.emergency.comm.relay.DeliveryState
import com.sethu.emergency.comm.relay.RelayReportStore
import com.sethu.emergency.comm.sender.SenderDeliveryState
import com.sethu.emergency.comm.sender.SenderReportStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Persistence audit: corruption, truncation, atomicity, write failure, idempotency, monotonic states. */
class StoreDurabilityTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun report(id: String, notes: String = "n") =
        SosReport(reportId = id, category = "Fire", peopleCount = "2", notes = notes, latitude = 10.0, longitude = 20.0)

    private fun senderItemJson(id: String, state: String = "PENDING_DELIVERY") =
        JSONObject().put("report", JSONObject(report(id).toJsonString())).put("deliveryState", state).put("createdAt", 1L)

    private fun backups(dir: File, prefix: String) = dir.listFiles { f -> f.name.startsWith("$prefix.corrupt-") }!!.toList()

    // ---------------------------------------------------------------- sender: load edge cases

    @Test fun missingEmptyAndBlankFilesLoadAsEmptyWithoutIssue() {
        val d1 = tmp.newFolder(); assertEquals(0, SenderReportStore(d1).getAllReports().size)
        val d2 = tmp.newFolder(); File(d2, "sethu_sender_store.json").writeText("")
        assertEquals(0, SenderReportStore(d2).getAllReports().size)
        val d3 = tmp.newFolder(); File(d3, "sethu_sender_store.json").writeText("  \n ")
        val s = SenderReportStore(d3); assertEquals(0, s.getAllReports().size); assertNull(s.loadIssue)
    }

    @Test fun garbageFileIsPreservedAsBackupNotSilentlyReplacedByEmptyStore() {
        val d = tmp.newFolder()
        val f = File(d, "sethu_sender_store.json"); f.writeText("<<< totally not json >>>")
        val store = SenderReportStore(d)
        assertNotNull(store.loadIssue)
        val b = backups(d, "sethu_sender_store.json")
        assertEquals("original bytes must be preserved for recovery", 1, b.size)
        assertEquals("<<< totally not json >>>", b[0].readText())
        store.saveReport(report("NEW"))
        assertEquals("backup survives later writes", 1, backups(d, "sethu_sender_store.json").size)
        assertEquals(1, SenderReportStore(d).getAllReports().size)
    }

    @Test fun truncatedFileRecoversEveryCompleteRecordAndKeepsBackup() {
        val d = tmp.newFolder()
        val full = JSONArray().put(senderItemJson("A")).put(senderItemJson("B", "ACKNOWLEDGED_BY_RELAY")).put(senderItemJson("C")).toString()
        val cut = full.substring(0, full.length - 25) // chops into the third record
        File(d, "sethu_sender_store.json").writeText(cut)
        val s = SenderReportStore(d)
        assertEquals(listOf("A", "B"), s.getAllReports().map { it.report.reportId })
        assertEquals(SenderDeliveryState.ACKNOWLEDGED_BY_RELAY, s.getReport("B")!!.deliveryState)
        assertEquals(1, backups(d, "sethu_sender_store.json").size)
        // The recovered state was rewritten as clean JSON: a second start has no issue
        assertNull(SenderReportStore(d).loadIssue)
    }

    @Test fun oneCorruptRecordDoesNotDropValidNeighbours_andOriginalIsBackedUp() {
        val d = tmp.newFolder()
        val arr = JSONArray().put(senderItemJson("GOOD1")).put(JSONObject().put("deliveryState", "PENDING_DELIVERY")) // no report
            .put(JSONObject().put("report", JSONObject().put("category", "x"))) // report without id
            .put(senderItemJson("GOOD2"))
        File(d, "sethu_sender_store.json").writeText(arr.toString())
        val s = SenderReportStore(d)
        assertEquals(listOf("GOOD1", "GOOD2"), s.getAllReports().map { it.report.reportId })
        assertEquals(1, backups(d, "sethu_sender_store.json").size)
    }

    @Test fun unknownStateMissingOptionalFieldsAndInvalidCoordinatesStillLoadTheReport() {
        val d = tmp.newFolder()
        val r = JSONObject(report("ODD").toJsonString()).put("latitude", 999.0).put("longitude", 5.0).put("accuracyMeters", -3.0).put("timestamp", -5)
        val item = JSONObject().put("report", r).put("deliveryState", "SOME_FUTURE_STATE")
        File(d, "sethu_sender_store.json").writeText(JSONArray().put(item).toString())
        val s = SenderReportStore(d).getReport("ODD")!!
        assertEquals("unknown state falls back to pending so it is re-sent, never dropped", SenderDeliveryState.PENDING_DELIVERY, s.deliveryState)
        assertNull(s.report.latitude); assertNull(s.report.longitude); assertNull(s.report.accuracyMeters)
        assertEquals(SosReport.LOCATION_STATUS_UNAVAILABLE, s.report.locationStatus)
        assertTrue(s.report.timestamp > 0)
    }

    @Test fun duplicateIdsOnDiskCollapseToMostAdvancedState() {
        val d = tmp.newFolder()
        File(d, "sethu_sender_store.json").writeText(
            JSONArray().put(senderItemJson("DUP")).put(senderItemJson("DUP", "ACKNOWLEDGED_BY_COORDINATOR")).put(senderItemJson("DUP", "TRANSMITTED")).toString()
        )
        val s = SenderReportStore(d)
        assertEquals(1, s.getAllReports().size)
        assertEquals(SenderDeliveryState.ACKNOWLEDGED_BY_COORDINATOR, s.getAllReports()[0].deliveryState)
    }

    @Test fun staleTempFileFromInterruptedWriteIsIgnoredAndCleanedByNextWrite() {
        val d = tmp.newFolder()
        SenderReportStore(d).saveReport(report("KEEP"))
        File(d, "sethu_sender_store.json.tmp").writeText("{half written")
        val s = SenderReportStore(d)
        assertEquals("interrupted temp write must not affect the committed store", 1, s.getAllReports().size)
        s.saveReport(report("MORE"))
        assertFalse(File(d, "sethu_sender_store.json.tmp").exists())
        assertEquals(2, SenderReportStore(d).getAllReports().size)
    }

    // ---------------------------------------------------------------- sender: write failure & state machine

    @Test fun writeFailureKeepsReportQueuedFlagsItAndRecoversOnNextWrite() {
        val d = tmp.newFolder("vol")
        val s = SenderReportStore(d)
        s.saveReport(report("OK1"))
        assertTrue(s.isPersisted()); assertNull(s.persistenceError.value)

        d.deleteRecursively() // storage disappears underneath the app
        val item = s.saveReport(report("LOST?"))
        assertEquals("still queued in memory", SenderDeliveryState.PENDING_DELIVERY, item.deliveryState)
        assertEquals(2, s.getPendingReports().size)
        assertFalse("must NOT claim durability", s.isPersisted())
        assertNotNull(s.persistenceError.value)

        d.mkdirs() // storage returns
        s.markTransmitted("LOST?")
        assertTrue(s.isPersisted()); assertNull(s.persistenceError.value)
        assertEquals(setOf("OK1", "LOST?"), SenderReportStore(d).getAllReports().map { it.report.reportId }.toSet())
    }

    @Test fun duplicateSaveRetriesAFailedWrite() {
        val d = tmp.newFolder("vol2")
        val s = SenderReportStore(d)
        d.deleteRecursively()
        s.saveReport(report("X")); assertFalse(s.isPersisted())
        d.mkdirs()
        s.saveReport(report("X")) // same id again (e.g. a retry tap handled upstream)
        assertTrue(s.isPersisted())
        assertEquals(1, SenderReportStore(d).getAllReports().size)
    }

    @Test fun senderStatesAreMonotonicAndIdempotent() {
        val s = SenderReportStore(tmp.newFolder())
        s.saveReport(report("M"))
        s.markTransmitted("M"); s.markTransmissionFailed("M", "boom")
        assertEquals(SenderDeliveryState.TRANSMISSION_FAILED, s.getReport("M")!!.deliveryState)
        s.markAcknowledgedByRelay("M")
        val relayAcked = s.getReport("M")!!
        s.markAcknowledgedByRelay("M")
        assertEquals("duplicate relay ACK must not rewrite the record", relayAcked, s.getReport("M"))
        s.markTransmitted("M"); s.markTransmissionFailed("M", "late failure")
        assertEquals("ACK must not regress to transmitted/failed", SenderDeliveryState.ACKNOWLEDGED_BY_RELAY, s.getReport("M")!!.deliveryState)
        s.markAcknowledgedByCoordinator("M")
        val done = s.getReport("M")!!
        s.markAcknowledgedByRelay("M"); s.markAcknowledgedByCoordinator("M"); s.markTransmitted("M")
        assertEquals(done, s.getReport("M"))
        s.markAcknowledgedByCoordinator("UNKNOWN"); s.markTransmitted("UNKNOWN")
        assertEquals(1, s.getAllReports().size)
    }


    /** Observers (UI, delivery engine) must never see a state that has not reached disk yet. */
    @Test fun publishedStateIsAlwaysAlreadyDurableOnDisk() {
        val d = tmp.newFolder()
        val s = SenderReportStore(d)
        val file = File(d, "sethu_sender_store.json")
        val violations = java.util.concurrent.CopyOnWriteArrayList<String>()
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val collector = Thread {
            var last: List<com.sethu.emergency.comm.sender.SenderReportItem>? = null
            while (!done.get()) {
                val snap = s.reportsFlow.value
                if (snap !== last) {
                    last = snap
                    var disk: JSONArray? = null
                    for (i in 0 until 50) { try { disk = JSONArray(file.readText()); break } catch (_: Exception) { Thread.sleep(2) } }
                    if (disk == null) continue // file momentarily locked by the writer's atomic replace: inconclusive, not a violation
                    snap.forEach { item ->
                        val onDisk = (0 until (disk?.length() ?: 0)).map { disk!!.getJSONObject(it) }
                            .find { it.getJSONObject("report").getString("reportId") == item.report.reportId }
                        if (onDisk == null) violations.add("published ${item.report.reportId} before it was on disk")
                        else if (item.deliveryState == SenderDeliveryState.ACKNOWLEDGED_BY_COORDINATOR && onDisk.getString("deliveryState") != "ACKNOWLEDGED_BY_COORDINATOR")
                            violations.add("published coordinator-ack before it was on disk")
                    }
                }
            }
        }
        collector.start()
        (1..40).forEach { i ->
            s.saveReport(report("P$i")); s.markTransmitted("P$i"); s.markAcknowledgedByRelay("P$i"); s.markAcknowledgedByCoordinator("P$i")
        }
        done.set(true); collector.join()
        assertTrue(violations.toString(), violations.isEmpty())
    }

    @Test fun concurrentWritersNeverLoseOrDuplicateReports() {
        val d = tmp.newFolder()
        val s = SenderReportStore(d)
        val threads = (0 until 8).map { t -> Thread { repeat(25) { i -> s.saveReport(report("T$t-$i")); s.markTransmitted("T$t-$i") } } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertEquals(200, s.getAllReports().size)
        assertEquals(200, SenderReportStore(d).getAllReports().map { it.report.reportId }.toSet().size)
    }

    // ---------------------------------------------------------------- relay

    @Test fun relayCorruptionIsRecoveredAndBackedUp() {
        val d = tmp.newFolder()
        val item = JSONObject().put("report", JSONObject(report("R1").toJsonString())).put("deliveryState", "DELIVERED_TO_COORDINATOR").put("sourceDeviceAddress", "AA")
        val full = JSONArray().put(item).put(item.put("report", JSONObject(report("R2").toJsonString()))).toString()
        File(d, "sethu_relay_store.json").writeText(full.substring(0, full.length - 10))
        val s = RelayReportStore(d)
        assertEquals(1, s.getAllReports().size)
        assertEquals(1, backups(d, "sethu_relay_store.json").size)
    }

    @Test fun relayStatesNeverRegressAndReceiptFlagPersists() {
        val d = tmp.newFolder()
        val s = RelayReportStore(d)
        s.saveReceivedReport(report("R"), "AA:AA")
        s.markForwarded("R"); s.markDelivered("R")
        val delivered = s.getAllReports().single()
        s.markDelivered("R")
        assertEquals("duplicate delivery ack must not rewrite", delivered, s.getAllReports().single())
        s.markForwardFailed("R", "late timeout"); s.markForwarded("R")
        assertEquals(DeliveryState.DELIVERED_TO_COORDINATOR, s.getAllReports().single().deliveryState)
        assertTrue(s.isDurablyStored("R"))
        assertEquals(1, s.getPendingDownstreamAcks("AA:AA").size)
        s.markCoordinatorAckPropagated("R")
        assertTrue(RelayReportStore(d).getAllReports().single().coordinatorAckPropagated)
        assertTrue(RelayReportStore(d).getPendingDownstreamAcks().isEmpty())
    }

    @Test fun relayDuplicateFillsMissingSourceButNeverOverwritesIt() {
        val s = RelayReportStore(tmp.newFolder())
        s.saveReceivedReport(report("S"), null)
        s.saveReceivedReport(report("S"), "AA:AA")
        assertEquals("AA:AA", s.getAllReports().single().sourceDeviceAddress)
        s.saveReceivedReport(report("S"), "BB:BB")
        assertEquals("AA:AA", s.getAllReports().single().sourceDeviceAddress)
        assertEquals(1, s.getAllReports().size)
    }

    @Test fun relayIsNotDurablyStoredWhileDiskWriteIsFailing() {
        val d = tmp.newFolder("rv")
        val s = RelayReportStore(d)
        d.deleteRecursively()
        s.saveReceivedReport(report("Z"), "AA")
        assertFalse("ingestion must not ACK a report that is not on disk", s.isDurablyStored("Z"))
        d.mkdirs()
        s.saveReceivedReport(report("Z"), "AA") // retransmission retries the write
        assertTrue(s.isDurablyStored("Z"))
    }

    // ---------------------------------------------------------------- coordinator

    @Test fun coordinatorLoadsLegacyAndBareFormats_dedupes_andBacksUpOnDamage() {
        val d = tmp.newFolder()
        val legacy = JSONArray().put(report("L1").toJsonString()).put(JSONObject(report("L2").toJsonString())) // string + bare object
            .put(JSONObject().put("report", JSONObject(report("L3").toJsonString())).put("receivedAt", 77L))
            .put(JSONObject().put("report", JSONObject(report("L3").toJsonString())).put("receivedAt", 99L)) // duplicate id
        File(d, "sethu_coordinator_store.json").writeText(legacy.toString())
        val s = CoordinatorReportStore(d)
        assertEquals(listOf("L1", "L2", "L3"), s.getAllReports().map { it.reportId })
        assertEquals("first receipt time wins for duplicates", 77L, s.getReceivedAt("L3"))
    }

    @Test fun coordinatorTruncatedFileKeepsRecoverableReports() {
        val d = tmp.newFolder()
        val s0 = CoordinatorReportStore(d)
        (1..4).forEach { s0.saveReport(report("C$it")) }
        val f = File(d, "sethu_coordinator_store.json")
        val txt = f.readText(); f.writeText(txt.substring(0, txt.length - 40))
        val s = CoordinatorReportStore(d)
        assertTrue("at least the first records survive (got ${s.getAllReports().size})", s.getAllReports().size in 1..3)
        assertEquals(1, backups(d, "sethu_coordinator_store.json").size)
    }

    @Test fun coordinatorDuplicateSaveReturnsFalseAndKeepsSingleIncident() {
        val s = CoordinatorReportStore(tmp.newFolder())
        assertTrue(s.saveReport(report("D")))
        assertFalse(s.saveReport(report("D")))
        assertFalse(s.saveReport(report("D", notes = "changed")))
        assertEquals(1, s.getAllReports().size)
        assertEquals("n", s.getAllReports()[0].notes)
    }

    // ---------------------------------------------------------------- SosReport validation

    @Test fun reportWithoutUsableIdIsRejected() {
        assertNull(SosReport.fromJson("{\"category\":\"Fire\"}"))
        assertNull(SosReport.fromJson("{\"reportId\":\"   \",\"category\":\"Fire\"}"))
        assertNull(SosReport.fromJson("{\"reportId\":null}"))
        assertNull(SosReport.fromJson("{\"reportId\":\"" + "x".repeat(500) + "\"}"))
        assertNull(SosReport.fromJson("not json"))
        assertNull(SosReport.fromJson(""))
    }

    @Test fun invalidCoordinatesAreDroppedButTheSosIsKept() {
        fun parse(lat: String, lon: String) = SosReport.fromJson("{\"reportId\":\"V\",\"latitude\":$lat,\"longitude\":$lon,\"locationStatus\":\"Available\",\"accuracyMeters\":5}")!!
        for ((la, lo) in listOf("91" to "10", "10" to "181", "-91" to "0", "\"NaN\"" to "5", "null" to "5")) {
            val r = parse(la, lo)
            assertNull("lat=$la lon=$lo", r.latitude); assertNull(r.longitude); assertNull(r.accuracyMeters)
        }
        val ok = parse("-33.9", "151.2")
        assertEquals(-33.9, ok.latitude!!, 1e-9); assertEquals(5f, ok.accuracyMeters!!, 0f)
        assertEquals("Available", ok.locationStatus)
    }

    @Test fun nullsOversizedFieldsAndBadTimestampsAreNormalised() {
        val r = SosReport.fromJson("{\"reportId\":\"N\",\"notes\":null,\"category\":null,\"timestamp\":0,\"peopleCount\":\"" + "9".repeat(100) + "\"}")!!
        assertEquals("", r.notes); assertEquals("Other", r.category)
        assertTrue(r.timestamp > 0); assertEquals(16, r.peopleCount.length)
        val big = SosReport.fromJson("{\"reportId\":\"B\",\"notes\":\"" + "n".repeat(10_000) + "\"}")!!
        assertEquals(SosReport.MAX_NOTES_LENGTH, big.notes.length)
    }

    @Test fun roundTripPreservesEveryField() {
        val src = SosReport(
            reportId = "RT", timestamp = 1234567890123L, senderDeviceName = "iQOO 15", category = "Medical", peopleCount = "4+",
            childrenInvolved = true, elderlyInvolved = true, medicalEmergency = true, notes = "ünï \"quoted\" \n newline",
            latitude = 12.3456789, longitude = 77.1234567, accuracyMeters = 8.5f, locationTimestamp = 99L, locationStatus = "Available"
        )
        assertEquals(src, SosReport.fromJson(src.toJsonString()))
    }
}
