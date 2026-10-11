package com.sethu.emergency.comm.sender

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
 * Honest delivery states for SOS Sender tracking.
 * Distinguishes local persistence from peer acknowledgement.
 *
 * Valid transitions (monotonic towards ACKNOWLEDGED_BY_COORDINATOR):
 * PENDING_DELIVERY -> TRANSMITTED -> ACKNOWLEDGED_BY_RELAY -> ACKNOWLEDGED_BY_COORDINATOR,
 * with TRANSMISSION_FAILED <-> TRANSMITTED/PENDING while unacknowledged. Acknowledged states never regress.
 */
enum class SenderDeliveryState {
    /** Report is durably persisted in local outbox, waiting for an eligible peer connection. */
    PENDING_DELIVERY,

    /** Transmitted over RFCOMM socket to connected peer, awaiting ACK. */
    TRANSMITTED,

    /** Exact report ID acknowledged by Relay device (TYPE_SOS_ACK). */
    ACKNOWLEDGED_BY_RELAY,

    /** Exact report ID acknowledged by Coordinator device (TYPE_COORDINATOR_ACK). */
    ACKNOWLEDGED_BY_COORDINATOR,

    /** Transmission attempt failed; report remains durably stored for retry. */
    TRANSMISSION_FAILED
}

data class SenderReportItem(
    val report: SosReport,
    val deliveryState: SenderDeliveryState,
    val createdAt: Long = System.currentTimeMillis(),
    val transmittedAt: Long? = null,
    val acknowledgedAt: Long? = null,
    val lastError: String? = null
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("report", JSONObject(report.toJsonString()))
        obj.put("deliveryState", deliveryState.name)
        obj.put("createdAt", createdAt)
        if (transmittedAt != null) obj.put("transmittedAt", transmittedAt)
        if (acknowledgedAt != null) obj.put("acknowledgedAt", acknowledgedAt)
        if (lastError != null) obj.put("lastError", lastError)
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): SenderReportItem? {
            return try {
                val reportJson = obj.getJSONObject("report")
                val report = SosReport.fromJson(reportJson.toString()) ?: return null
                val stateName = obj.optString("deliveryState", SenderDeliveryState.PENDING_DELIVERY.name)
                // Unknown/future state names fall back to PENDING so the report is re-sent, never dropped.
                val state = try {
                    SenderDeliveryState.valueOf(stateName)
                } catch (_: Exception) {
                    SenderDeliveryState.PENDING_DELIVERY
                }
                SenderReportItem(
                    report = report,
                    deliveryState = state,
                    createdAt = obj.optLong("createdAt", report.timestamp),
                    transmittedAt = if (obj.has("transmittedAt")) obj.optLong("transmittedAt") else null,
                    acknowledgedAt = if (obj.has("acknowledgedAt")) obj.optLong("acknowledgedAt") else null,
                    lastError = if (obj.has("lastError")) obj.optString("lastError") else null
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Thread-safe, durable local storage for SOS reports created on the Sender device.
 * Guarantees that reports created via One-Tap SOS or structured form are persisted to disk
 * before any transmission attempt, surviving process kills, app navigation, and reboots.
 *
 * If a disk write fails the report stays queued in memory, [persistenceError] is set, [isPersisted] is false,
 * and the write is retried on the next mutation; callers must not present such a report as safely stored.
 */
class SenderReportStore(private val storageDir: File) {

    constructor(context: Context) : this(context.filesDir)

    companion object {
        private const val FILENAME = "sethu_sender_store.json"
    }

    private val file = DurableJsonArrayFile(storageDir, FILENAME)
    private val lock = Any()

    private val _reportsFlow = MutableStateFlow<List<SenderReportItem>>(emptyList())
    val reportsFlow: StateFlow<List<SenderReportItem>> = _reportsFlow.asStateFlow()

    private val _persistenceError = MutableStateFlow<String?>(null)
    /** Non-null while the latest in-memory state could not be written to disk. */
    val persistenceError: StateFlow<String?> = _persistenceError.asStateFlow()

    /** Set when the store file was damaged/partially unreadable at load; originals are kept as .corrupt-* files. */
    var loadIssue: String? = null
        private set

    @Volatile private var dirty = false

    init {
        loadFromDisk()
    }

    private fun loadFromDisk() {
        synchronized(lock) {
            val result = file.load()
            val list = result.elements.mapNotNull { e ->
                (e as? JSONObject)?.let { SenderReportItem.fromJson(it) }
            }
            val unparsed = result.elements.size - list.size
            loadIssue = result.issue ?: if (unparsed > 0) "$unparsed unreadable record(s) skipped" else null
            // Duplicate IDs on disk collapse to the most advanced delivery state (never lose an acknowledgement).
            val deduped = list.groupBy { it.report.reportId }.map { (_, dupes) -> dupes.maxByOrNull { stateRank(it.deliveryState) }!! }
            _reportsFlow.value = deduped
            if (unparsed > 0 && result.backup == null) file.preserveOriginal()
            if (loadIssue != null && !result.unreadable) persistToDisk(deduped) // rewrite a clean file; the damaged original is preserved as .corrupt-*
        }
    }

    private fun persistToDisk(list: List<SenderReportItem>): Boolean {
        synchronized(lock) {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            val ok = file.write(arr.toString(2))
            dirty = !ok
            _persistenceError.value = if (ok) null else "Could not write the SOS outbox to storage"
            return ok
        }
    }

    private fun stateRank(s: SenderDeliveryState): Int = when (s) {
        SenderDeliveryState.ACKNOWLEDGED_BY_COORDINATOR -> 4
        SenderDeliveryState.ACKNOWLEDGED_BY_RELAY -> 3
        SenderDeliveryState.TRANSMITTED -> 2
        SenderDeliveryState.TRANSMISSION_FAILED -> 1
        SenderDeliveryState.PENDING_DELIVERY -> 0
    }

    /** True when every in-memory change has been written to disk. */
    fun isPersisted(): Boolean = !dirty

    /** Applies [transform] to the matching report; writes only when something changed (or a prior write failed). */
    private fun update(reportId: String, transform: (SenderReportItem) -> SenderReportItem) {
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
            // Persist FIRST, publish second: anything observing the flow (UI, delivery engine) only ever sees
            // state that has already reached disk (or is explicitly flagged non-durable via persistenceError).
            if (changed || dirty) persistToDisk(updated)
            if (changed) _reportsFlow.value = updated
        }
    }

    /**
     * Persist a newly created SOS report to the durable outbox.
     * Deduplicates strictly by original reportId.
     */
    fun saveReport(report: SosReport): SenderReportItem {
        synchronized(lock) {
            val current = _reportsFlow.value
            val existing = current.find { it.report.reportId == report.reportId }
            if (existing != null) {
                if (dirty) persistToDisk(current)
                return existing
            }

            val newItem = SenderReportItem(
                report = report,
                deliveryState = SenderDeliveryState.PENDING_DELIVERY,
                createdAt = System.currentTimeMillis()
            )
            val updated = current + newItem
            persistToDisk(updated)
            _reportsFlow.value = updated
            return newItem
        }
    }

    fun markTransmitted(reportId: String) = update(reportId) { item ->
        if (item.deliveryState == SenderDeliveryState.ACKNOWLEDGED_BY_RELAY ||
            item.deliveryState == SenderDeliveryState.ACKNOWLEDGED_BY_COORDINATOR
        ) item
        else item.copy(deliveryState = SenderDeliveryState.TRANSMITTED, transmittedAt = System.currentTimeMillis(), lastError = null)
    }

    fun markAcknowledgedByRelay(reportId: String) = update(reportId) { item ->
        if (item.deliveryState == SenderDeliveryState.ACKNOWLEDGED_BY_COORDINATOR ||
            item.deliveryState == SenderDeliveryState.ACKNOWLEDGED_BY_RELAY
        ) item
        else item.copy(deliveryState = SenderDeliveryState.ACKNOWLEDGED_BY_RELAY, acknowledgedAt = System.currentTimeMillis(), lastError = null)
    }

    fun markAcknowledgedByCoordinator(reportId: String) = update(reportId) { item ->
        if (item.deliveryState == SenderDeliveryState.ACKNOWLEDGED_BY_COORDINATOR) item
        else item.copy(deliveryState = SenderDeliveryState.ACKNOWLEDGED_BY_COORDINATOR, acknowledgedAt = System.currentTimeMillis(), lastError = null)
    }

    fun markTransmissionFailed(reportId: String, error: String) = update(reportId) { item ->
        if (item.deliveryState == SenderDeliveryState.ACKNOWLEDGED_BY_RELAY ||
            item.deliveryState == SenderDeliveryState.ACKNOWLEDGED_BY_COORDINATOR
        ) item
        else item.copy(deliveryState = SenderDeliveryState.TRANSMISSION_FAILED, lastError = error)
    }

    fun getPendingReports(): List<SenderReportItem> {
        return _reportsFlow.value.filter {
            it.deliveryState == SenderDeliveryState.PENDING_DELIVERY ||
            it.deliveryState == SenderDeliveryState.TRANSMISSION_FAILED ||
            it.deliveryState == SenderDeliveryState.TRANSMITTED
        }
    }

    fun getAllReports(): List<SenderReportItem> = _reportsFlow.value

    fun getReport(reportId: String): SenderReportItem? =
        _reportsFlow.value.find { it.report.reportId == reportId }

    fun clearAll() {
        synchronized(lock) {
            _reportsFlow.value = emptyList()
            file.delete()
            dirty = false
            _persistenceError.value = null
        }
    }
}

