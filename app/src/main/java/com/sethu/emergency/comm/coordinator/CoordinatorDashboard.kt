package com.sethu.emergency.comm.coordinator

import com.sethu.emergency.comm.model.SosReport

/**
 * Pure presentation rules for the Coordinator dashboard, kept out of the Compose screen so they can be tested.
 *
 * Ordering is by when THIS coordinator received the report, not by the sender's creation timestamp: the sender's
 * clock may be minutes off (or the SOS may have waited in a relay queue), and a freshly delivered incident must
 * never sink below stale items just because its creation time is older.
 */
object CoordinatorDashboard {

    fun newestReceivedFirst(items: List<CoordinatorReportItem>): List<CoordinatorReportItem> =
        items.sortedWith(
            compareByDescending<CoordinatorReportItem> { it.receivedAt }.thenByDescending { it.report.timestamp }
        )

    fun latestReceived(items: List<CoordinatorReportItem>): CoordinatorReportItem? =
        newestReceivedFirst(items).firstOrNull()

    /** Items whose IDs were not present in [previousIds]. */
    fun arrivals(previousIds: Set<String>, items: List<CoordinatorReportItem>): List<CoordinatorReportItem> =
        items.filter { it.report.reportId !in previousIds }

    /**
     * An active priority filter must never silently hide a report that has just arrived: if any new arrival
     * would be filtered out, the filter is cleared so the new SOS is visible.
     */
    fun shouldClearFilter(activeFilter: IncidentPriority?, arrived: List<SosReport>): Boolean =
        activeFilter != null && arrived.any { PriorityClassifier.evaluate(it).priority != activeFilter }
}
