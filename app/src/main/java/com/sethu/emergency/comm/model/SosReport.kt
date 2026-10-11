package com.sethu.emergency.comm.model

import org.json.JSONObject
import java.util.UUID

/**
 * Structured, versioned SOS Emergency Report model.
 * Serializable to/from JSON for Bluetooth transport.
 */
data class SosReport(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val reportId: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val senderDeviceName: String = "Unknown",
    val category: String, // Flood, Medical, Fire, Trapped, Injury, Other
    val peopleCount: String, // 1, 2, 3, 4+
    val childrenInvolved: Boolean = false,
    val elderlyInvolved: Boolean = false,
    val medicalEmergency: Boolean = false,
    val notes: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMeters: Float? = null,
    val locationTimestamp: Long? = null,
    val locationStatus: String = LOCATION_STATUS_UNAVAILABLE // Available, Permission denied, Unavailable, Timed out
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1

        const val LOCATION_STATUS_AVAILABLE = "Available"
        const val LOCATION_STATUS_PERMISSION_DENIED = "Permission denied"
        const val LOCATION_STATUS_UNAVAILABLE = "Unavailable"
        const val LOCATION_STATUS_TIMED_OUT = "Timed out"

        const val MAX_ID_LENGTH = 128
        const val MAX_NOTES_LENGTH = 4000

        /**
         * Parses and validates an SOS report. Returns null (never a guessed report) when the payload is not
         * JSON or has no usable reportId, so a corrupt payload can never be persisted or acknowledged.
         * Out-of-range coordinates are dropped (the SOS itself is kept) and flagged as location Unavailable.
         */
        fun fromJson(jsonStr: String): SosReport? {
            return try {
                val obj = JSONObject(jsonStr)
                fun str(key: String, default: String): String =
                    if (obj.has(key) && !obj.isNull(key)) obj.optString(key, default) else default
                fun dbl(key: String): Double? =
                    if (obj.has(key) && !obj.isNull(key)) obj.optDouble(key).takeIf { it.isFinite() } else null

                val reportId = str("reportId", "").trim()
                if (reportId.isEmpty() || reportId.length > MAX_ID_LENGTH) return null

                var lat = dbl("latitude")
                var lon = dbl("longitude")
                var status = str("locationStatus", LOCATION_STATUS_UNAVAILABLE).take(40)
                val coordsValid = lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0
                if (!coordsValid) {
                    if (lat != null || lon != null) status = LOCATION_STATUS_UNAVAILABLE
                    lat = null
                    lon = null
                }
                val accuracy = if (coordsValid) dbl("accuracyMeters")?.takeIf { it >= 0 }?.toFloat() else null
                val ts = obj.optLong("timestamp", 0L)

                SosReport(
                    schemaVersion = obj.optInt("schemaVersion", CURRENT_SCHEMA_VERSION),
                    reportId = reportId,
                    timestamp = if (ts > 0) ts else System.currentTimeMillis(),
                    senderDeviceName = str("senderDeviceName", "Unknown").take(64),
                    category = str("category", "Other").take(32),
                    peopleCount = str("peopleCount", "1").take(16),
                    childrenInvolved = obj.optBoolean("childrenInvolved", false),
                    elderlyInvolved = obj.optBoolean("elderlyInvolved", false),
                    medicalEmergency = obj.optBoolean("medicalEmergency", false),
                    notes = str("notes", "").take(MAX_NOTES_LENGTH),
                    latitude = lat,
                    longitude = lon,
                    accuracyMeters = accuracy,
                    locationTimestamp = if (coordsValid && obj.has("locationTimestamp") && !obj.isNull("locationTimestamp")) obj.optLong("locationTimestamp") else null,
                    locationStatus = status
                )
            } catch (e: Exception) {
                null
            }
        }
    }
    fun toJsonString(): String {
        val obj = JSONObject()
        obj.put("schemaVersion", schemaVersion)
        obj.put("reportId", reportId)
        obj.put("timestamp", timestamp)
        obj.put("senderDeviceName", senderDeviceName)
        obj.put("category", category)
        obj.put("peopleCount", peopleCount)
        obj.put("childrenInvolved", childrenInvolved)
        obj.put("elderlyInvolved", elderlyInvolved)
        obj.put("medicalEmergency", medicalEmergency)
        obj.put("notes", notes)
        if (latitude != null) obj.put("latitude", latitude)
        if (longitude != null) obj.put("longitude", longitude)
        if (accuracyMeters != null) obj.put("accuracyMeters", accuracyMeters.toDouble())
        if (locationTimestamp != null) obj.put("locationTimestamp", locationTimestamp)
        obj.put("locationStatus", locationStatus)
        return obj.toString()
    }
}
