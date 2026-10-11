package com.sethu.emergency.comm.coordinator

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
 * Persisted entry on the Coordinator device, tracking both the original SOS report
 * and the exact timestamp when it was received by the Coordinator over RFCOMM.
 */
data class CoordinatorReportItem(
    val report: SosReport,
    val receivedAt: Long = System.currentTimeMillis()
)

/**
 * Durable local storage for received forwarded SOS reports on the Coordinator device (Motorola).
 * Deduplicates strictly by original reportId.
 * Preserves complete report structure for triage and tracks receipt time separately from creation time.
 * Backward compatible with existing stored JSON (legacy array-of-strings and bare-report formats).
 */
class CoordinatorReportStore(private val storageDir: File) {

    constructor(context: Context) : this(context.filesDir)

    companion object {
        private const val FILENAME = "sethu_coordinator_store.json"
    }

    private val file = DurableJsonArrayFile(storageDir, FILENAME)
    private val lock = Any()

    private val _itemsFlow = MutableStateFlow<List<CoordinatorReportItem>>(emptyList())
    val itemsFlow: StateFlow<List<CoordinatorReportItem>> = _itemsFlow.asStateFlow()

    private val _reportsFlow = MutableStateFlow<List<SosReport>>(emptyList())
    val reportsFlow: StateFlow<List<SosReport>> = _reportsFlow.asStateFlow()

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
            val list = LinkedHashMap<String, CoordinatorReportItem>()
            for (elem in result.elements) {
                val item: CoordinatorReportItem? = when (elem) {
                    is JSONObject -> {
                        // Structured format {report, receivedAt}, or a bare serialized report
                        val report = try {
                            if (elem.has("report")) SosReport.fromJson(elem.getJSONObject("report").toString())
                            else SosReport.fromJson(elem.toString())
                        } catch (_: Exception) {
                            null
                        }
                        report?.let { CoordinatorReportItem(it, elem.optLong("receivedAt", it.timestamp)) }
                    }
                    is String -> SosReport.fromJson(elem)?.let { CoordinatorReportItem(it, it.timestamp) } // legacy
                    else -> null
                }
                if (item != null && !list.containsKey(item.report.reportId)) list[item.report.reportId] = item
            }
            val unparsed = result.elements.size - list.size
            loadIssue = result.issue ?: if (unparsed > 0) "$unparsed unreadable or duplicate record(s) skipped" else null
            publish(list.values.toList())
            if (unparsed > 0 && result.backup == null) file.preserveOriginal()
            if (loadIssue != null && !result.unreadable) persistToDisk(list.values.toList())
        }
    }

    private fun publish(items: List<CoordinatorReportItem>) {
        _itemsFlow.value = items
        _reportsFlow.value = items.map { it.report }
    }

    private fun persistToDisk(list: List<CoordinatorReportItem>): Boolean {
        synchronized(lock) {
            val arr = JSONArray()
            list.forEach { item ->
                val obj = JSONObject()
                obj.put("report", JSONObject(item.report.toJsonString()))
                obj.put("receivedAt", item.receivedAt)
                arr.put(obj)
            }
            val ok = file.write(arr.toString(2))
            dirty = !ok
            _persistenceError.value = if (ok) null else "Could not write received reports to storage"
            return ok
        }
    }

    /**
     * Store a received report on Coordinator.
     * Records Coordinator receipt time separately from original SOS report creation time.
     * Returns true if newly stored, false if duplicate.
     */
    fun saveReport(report: SosReport, receivedAt: Long = System.currentTimeMillis()): Boolean {
        synchronized(lock) {
            val current = _itemsFlow.value
            if (current.any { it.report.reportId == report.reportId }) {
                if (dirty) persistToDisk(current) // retry a previously failed write
                return false
            }
            val updated = current + CoordinatorReportItem(report, receivedAt)
            // Persist FIRST, publish second: observers (dashboard) never see a report that is not yet on disk,
            // and isDurablyStored() reflects the write result before any ACK can be sent.
            persistToDisk(updated)
            publish(updated)
            return true
        }
    }

    /** True when the report is in the store AND every in-memory change has reached disk. */
    fun isDurablyStored(reportId: String): Boolean =
        !dirty && _itemsFlow.value.any { it.report.reportId == reportId }

    fun getAllReports(): List<SosReport> = _reportsFlow.value

    fun getAllItems(): List<CoordinatorReportItem> = _itemsFlow.value

    fun getReceivedAt(reportId: String): Long? =
        _itemsFlow.value.find { it.report.reportId == reportId }?.receivedAt

    /**
     * Re-reads persistent storage from disk into memory and publishes the latest reports.
     * Returns the number of loaded reports.
     */
    fun reloadFromDisk(): Int {
        loadFromDisk()
        return _itemsFlow.value.size
    }

    fun clearAll() {
        synchronized(lock) {
            publish(emptyList())
            file.delete()
            dirty = false
            _persistenceError.value = null
        }
    }
}

