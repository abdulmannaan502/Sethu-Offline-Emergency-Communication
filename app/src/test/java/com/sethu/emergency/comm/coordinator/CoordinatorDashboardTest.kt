package com.sethu.emergency.comm.coordinator

import com.sethu.emergency.comm.model.SosReport
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList

class CoordinatorDashboardTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun report(id: String, createdAt: Long, notes: String = "needs help", category: String = "Medical") = SosReport(
        reportId = id,
        category = category,
        peopleCount = "1",
        notes = notes,
        latitude = 12.0,
        longitude = 77.0,
        senderDeviceName = "iQOO 15",
        timestamp = createdAt
    )

    // 24. The dashboard's store updates immediately, and only AFTER the report is on disk
    @Test
    fun observersSeeTheReportOnlyOnceItIsOnDiskAndImmediately() = runBlocking {
        val dir = tmp.newFolder()
        val store = CoordinatorReportStore(dir)
        val seenOnDisk = CopyOnWriteArrayList<Boolean>()
        val job = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            store.itemsFlow.collect { items ->
                if (items.isNotEmpty()) {
                    // reload from disk in a second store instance: must already contain what we were just told about
                    val onDisk = CoordinatorReportStore(dir).getAllReports().map { it.reportId }
                    seenOnDisk.add(items.all { it.report.reportId in onDisk })
                }
            }
        }
        assertTrue(store.saveReport(report("SOS-1", 1000)))
        assertTrue(store.isDurablyStored("SOS-1"))
        assertEquals("flow emitted synchronously with the save", listOf(true), seenOnDisk.toList())
        job.cancel()
    }

    // 25. Survives process restart (a brand-new store instance on the same directory)
    @Test
    fun reportsSurviveRestartWithTheirReceiveTime() {
        val dir = tmp.newFolder()
        CoordinatorReportStore(dir).saveReport(report("SOS-R", 1000), receivedAt = 5000)
        val reopened = CoordinatorReportStore(dir)
        assertEquals(listOf("SOS-R"), reopened.getAllReports().map { it.reportId })
        assertEquals(5000L, reopened.getReceivedAt("SOS-R"))
    }

    @Test
    fun duplicateTransmissionDoesNotCreateASecondIncident() {
        val store = CoordinatorReportStore(tmp.newFolder())
        assertTrue(store.saveReport(report("SOS-D", 1000)))
        assertFalse(store.saveReport(report("SOS-D", 1000)))
        assertEquals(1, store.getAllReports().size)
    }

    // The new SOS must be easy to find: ordered by when THIS coordinator received it, not by the sender's clock.
    @Test
    fun latestReceivedIgnoresAnOlderSenderClock() {
        val stale = CoordinatorReportItem(report("OLD", createdAt = 9_000_000), receivedAt = 1_000)
        val fresh = CoordinatorReportItem(report("NEW", createdAt = 1_000), receivedAt = 2_000)
        assertEquals("NEW", CoordinatorDashboard.latestReceived(listOf(stale, fresh))!!.report.reportId)
        assertEquals(listOf("NEW", "OLD"), CoordinatorDashboard.newestReceivedFirst(listOf(stale, fresh)).map { it.report.reportId })
    }

    @Test
    fun arrivalsAreOnlyTheReportsNotSeenBefore() {
        val a = CoordinatorReportItem(report("A", 1), 1)
        val b = CoordinatorReportItem(report("B", 2), 2)
        assertEquals(listOf("B"), CoordinatorDashboard.arrivals(setOf("A"), listOf(a, b)).map { it.report.reportId })
    }

    // An active filter must never silently hide a new SOS
    @Test
    fun activeFilterIsClearedWhenANewArrivalWouldBeHiddenByIt() {
        val critical = report("C", 1, notes = "patient is unconscious and not breathing")
        val plain = report("P", 2, notes = "flood water rising, need evacuation assistance")
        val critEval = PriorityClassifier.evaluate(critical).priority
        val plainEval = PriorityClassifier.evaluate(plain).priority
        assertTrue("fixture needs two different priorities", critEval != plainEval)

        assertTrue(CoordinatorDashboard.shouldClearFilter(critEval, listOf(plain)))
        assertFalse("arrival matches the filter: keep it", CoordinatorDashboard.shouldClearFilter(critEval, listOf(critical)))
        assertFalse("no filter active", CoordinatorDashboard.shouldClearFilter(null, listOf(plain)))
        assertFalse("no arrivals", CoordinatorDashboard.shouldClearFilter(critEval, emptyList()))
    }
}
