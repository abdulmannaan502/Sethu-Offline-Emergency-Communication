package com.sethu.emergency.comm.relay

import com.sethu.emergency.comm.model.SosReport
import org.json.JSONObject

/**
 * Honest delivery states for relay hop-by-hop tracking in Phase 6.
 * Accurately tracks stages between iQOO 15 (Sender), Vivo Y15 (Relay), and Motorola (Coordinator).
 */
enum class DeliveryState {
    /** Vivo has received and validated the report from SOS Sender. */
    RECEIVED_BY_RELAY,

    /** Vivo has successfully persisted the report into local durable storage. */
    SAVED_BY_RELAY,

    /** Report is stored locally and queued for forwarding to Motorola Coordinator. */
    PENDING_FORWARD,

    /** Vivo has transmitted the report over RFCOMM socket to Coordinator, awaiting ACK. */
    FORWARDED_TO_COORDINATOR,

    /** Motorola Coordinator has received and acknowledged the exact original report ID. */
    DELIVERED_TO_COORDINATOR,

    /** Forwarding transmission to Coordinator failed; report remains queued for retry. */
    FORWARD_FAILED
}

data class RelayReportItem(
    val report: SosReport,
    val deliveryState: DeliveryState,
    val receivedAt: Long = System.currentTimeMillis(),
    val forwardedAt: Long? = null,
    val deliveredAt: Long? = null,
    val lastError: String? = null,
    val sourceDeviceAddress: String? = null,
    val coordinatorAckPropagated: Boolean = false
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("report", JSONObject(report.toJsonString()))
        obj.put("deliveryState", deliveryState.name)
        obj.put("receivedAt", receivedAt)
        if (forwardedAt != null) obj.put("forwardedAt", forwardedAt)
        if (deliveredAt != null) obj.put("deliveredAt", deliveredAt)
        if (lastError != null) obj.put("lastError", lastError)
        if (sourceDeviceAddress != null) obj.put("sourceDeviceAddress", sourceDeviceAddress)
        obj.put("coordinatorAckPropagated", coordinatorAckPropagated)
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): RelayReportItem? {
            return try {
                val reportJson = obj.getJSONObject("report")
                val report = SosReport.fromJson(reportJson.toString()) ?: return null
                val stateName = obj.optString("deliveryState", DeliveryState.SAVED_BY_RELAY.name)
                val state = try {
                    DeliveryState.valueOf(stateName)
                } catch (_: Exception) {
                    DeliveryState.SAVED_BY_RELAY
                }
                RelayReportItem(
                    report = report,
                    deliveryState = state,
                    receivedAt = obj.optLong("receivedAt", System.currentTimeMillis()),
                    forwardedAt = if (obj.has("forwardedAt")) obj.optLong("forwardedAt") else null,
                    deliveredAt = if (obj.has("deliveredAt")) obj.optLong("deliveredAt") else null,
                    lastError = if (obj.has("lastError")) obj.optString("lastError") else null,
                    sourceDeviceAddress = if (obj.has("sourceDeviceAddress")) obj.optString("sourceDeviceAddress") else null,
                    coordinatorAckPropagated = obj.optBoolean("coordinatorAckPropagated", false)
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
