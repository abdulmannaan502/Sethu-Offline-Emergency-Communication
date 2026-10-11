package com.sethu.emergency.comm.relay

import android.content.Context
import com.sethu.emergency.comm.model.SosReport
import com.sethu.emergency.comm.util.DurableJsonArrayFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Thread-safe, persistent local storage for Relay SOS reports.
 * Uses atomic JSON file persistence in the app-internal files directory.
 * Ensures received reports survive process kills, disconnects, and reconnections.
 *
 * Final-ACK bookkeeping: [RelayReportItem.coordinatorAckPropagated] becomes true ONLY when the originating
 * Sender has confirmed (receipt) that it persisted the coordinator ACK - never merely because a socket write
 * succeeded.
 */
class RelayReportStore(private val storageDir: File) {

    constructor(context: Context) : this(context.filesDir)

    companion object {
        private const val FILENAME = "sethu_relay_store.json"
    }

    private val file = DurableJsonArrayFile(storageDir, FILENAME)
    private val lock = Any()

    private val _reportsFlow = MutableStateFlow<List<RelayReportItem>>(emptyList())
    val reportsFlow: StateFlow<List<RelayReportItem>> = _reportsFlow.asStateFlow()

    private val _persistenceError = MutableStateFlow<String?>(null)
    val persistenceError: StateFlow<String?> = _persistenceError.asStateFlow()

    var loadIssue: String? = null
        private set

    @Volatile private var dirty = false

    init {
        loadFromDisk()
    }

    private fun loadFromDisk() {
        synchronized(lock) {
            val result = file.load()
            val list = result.elements.mapNotNull { e -> (e as? JSONObject)?.let { RelayReportItem.fromJson(it) } }
            val unparsed = result.elements.size - list.size
            loadIssue = result.issue ?: if (unparsed > 0) "$unparsed unreadable record(s) skipped" else null
            // Duplicate IDs on disk (e.g. from an older build) collapse to the most advanced record.
            _reportsFlow.value = list.groupBy { it.report.reportId }.map { (_, dupes) ->
                dupes.maxByOrNull {
                    (if (it.coordinatorAckPropagated) 2 else 0) + (if (it.deliveryState == DeliveryState.DELIVERED_TO_COORDINATOR) 1 else 0)
                }!!
            }
            if (unparsed > 0 && result.backup == null) file.preserveOriginal()
            if (loadIssue != null && !result.unreadable) persistToDisk(_reportsFlow.value)
        }
    }

    private fun persistToDisk(list: List<RelayReportItem>): Boolean {
        synchronized(lock) {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            val ok = file.write(arr.toString(2))
            dirty = !ok
            _persistenceError.value = if (ok) null else "Could not write the relay queue to storage"
            return ok
        }
    }

    /** True when the report is in the store AND every in-memory change has reached disk. */
    fun isDurablyStored(reportId: String): Boolean =
        !dirty && _reportsFlow.value.any { it.report.reportId == reportId }

    private fun update(reportId: String, transform: (RelayReportItem) -> RelayReportItem) {
        synchronized(lock) {
            val current = _reportsFlow.value
            var changed = false
            val updated = current.map { item ->
                if (item.report.reportId == reportId) {
                    val n = transform(item)
                    if (n != item) changed = true
                    n
                } else item
            }
            // Persist FIRST, publish second (see SenderReportStore).
            if (changed || dirty) persistToDisk(updated)
            if (changed) _reportsFlow.value = updated
        }
    }

    /**
     * Ingest a received report from SOS Sender.
     * Deduplicates strictly by original reportId. New reports start as PENDING_FORWARD.
     */
    fun saveReceivedReport(report: SosReport, sourceAddress: String? = null): RelayReportItem {
        synchronized(lock) {
            val current = _reportsFlow.value
            val existing = current.find { it.report.reportId == report.reportId }
            if (existing != null) {
                // Duplicate delivery: keep status (e.g. already delivered); only fill a missing source address.
                if (existing.sourceDeviceAddress.isNullOrBlank() && !sourceAddress.isNullOrBlank()) {
                    val fixed = existing.copy(sourceDeviceAddress = sourceAddress)
                    val updated = current.map { if (it.report.reportId == report.reportId) fixed else it }
                    persistToDisk(updated)
                    _reportsFlow.value = updated
                    return fixed
                }
                if (dirty) persistToDisk(current)
                return existing
            }

            val newItem = RelayReportItem(
                report = report,
                deliveryState = DeliveryState.PENDING_FORWARD,
                receivedAt = System.currentTimeMillis(),
                sourceDeviceAddress = sourceAddress,
                coordinatorAckPropagated = false
            )
            val updated = current + newItem
            persistToDisk(updated)
            _reportsFlow.value = updated
            return newItem
        }
    }

    /**
     * Mark the final coordinator ACK as confirmed by the originating sender (receipt received).
     * Only meaningful once the report is DELIVERED_TO_COORDINATOR.
     */
    fun markCoordinatorAckPropagated(reportId: String) = update(reportId) { item ->
        if (item.coordinatorAckPropagated) item else item.copy(coordinatorAckPropagated = true)
    }

    /**
     * Reports delivered to Coordinator whose final ACK has not yet been confirmed by the originating sender.
     */
    fun getPendingDownstreamAcks(forDeviceAddress: String? = null, forDeviceName: String? = null): List<RelayReportItem> {
        return _reportsFlow.value.filter { item ->
            if (item.deliveryState != DeliveryState.DELIVERED_TO_COORDINATOR || item.coordinatorAckPropagated) {
                return@filter false
            }
            if (!forDeviceAddress.isNullOrBlank() && !item.sourceDeviceAddress.isNullOrBlank()) {
                item.sourceDeviceAddress.equals(forDeviceAddress, ignoreCase = true)
            } else if (!forDeviceAddress.isNullOrBlank() && item.sourceDeviceAddress.isNullOrBlank()) {
                // Address specified but item has no recorded address: never deliver to an unverified address
                false
            } else if (!forDeviceName.isNullOrBlank() && forDeviceAddress.isNullOrBlank()) {
                item.report.senderDeviceName.equals(forDeviceName, ignoreCase = true)
            } else {
                forDeviceAddress == null && forDeviceName == null
            }
        }
    }

    /** Mark that a forward to Coordinator was attempted (awaiting ACK). Never regresses a delivered report. */
    fun markForwarded(reportId: String) = update(reportId) { item ->
        if (item.deliveryState == DeliveryState.DELIVERED_TO_COORDINATOR) item
        else item.copy(deliveryState = DeliveryState.FORWARDED_TO_COORDINATOR, forwardedAt = System.currentTimeMillis())
    }

    /** Mark that Coordinator explicitly acknowledged receipt of this exact report ID. */
    fun markDelivered(reportId: String) = update(reportId) { item ->
        if (item.deliveryState == DeliveryState.DELIVERED_TO_COORDINATOR) item
        else item.copy(deliveryState = DeliveryState.DELIVERED_TO_COORDINATOR, deliveredAt = System.currentTimeMillis(), lastError = null)
    }

    /** Record a forwarding error. Report stays queued with FORWARD_FAILED for retry; delivered reports never regress. */
    fun markForwardFailed(reportId: String, error: String) = update(reportId) { item ->
        if (item.deliveryState == DeliveryState.DELIVERED_TO_COORDINATOR) item
        else item.copy(deliveryState = DeliveryState.FORWARD_FAILED, lastError = error)
    }

    /** Retrieve all reports that still need forwarding or retry. */
    fun getPendingReports(): List<RelayReportItem> {
        return _reportsFlow.value.filter {
            it.deliveryState == DeliveryState.PENDING_FORWARD ||
            it.deliveryState == DeliveryState.SAVED_BY_RELAY ||
            it.deliveryState == DeliveryState.FORWARD_FAILED ||
            it.deliveryState == DeliveryState.FORWARDED_TO_COORDINATOR
        }
    }

    fun getAllReports(): List<RelayReportItem> = _reportsFlow.value

    fun clearAll() {
        synchronized(lock) {
            _reportsFlow.value = emptyList()
            file.delete()
            dirty = false
            _persistenceError.value = null
        }
    }
}

