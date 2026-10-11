package com.sethu.emergency.comm.model

import org.json.JSONObject
import java.util.UUID

/**
 * Versioned message model for peer-to-peer transport over RFCOMM.
 */
data class SethuMessage(
    val version: Int = CURRENT_VERSION,
    val messageId: String = UUID.randomUUID().toString(),
    val senderDeviceName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val messageType: String = TYPE_TEST_PING,
    val payload: String
) {
    companion object {
        const val CURRENT_VERSION = 1

        const val TYPE_TEST_PING = "TEST_PING"
        const val TYPE_TEST_REPLY = "TEST_REPLY"
        const val TYPE_TEXT = "TEXT"
        const val TYPE_SOS_REPORT = "SOS_REPORT"
        const val TYPE_SOS_ACK = "SOS_ACK"
        const val TYPE_COORDINATOR_ACK = "COORDINATOR_ACK"
        const val TYPE_ROLE_ANNOUNCE = "ROLE_ANNOUNCE"

        /**
         * Sent by the originating SOS Sender back to the Relay after it has durably persisted a
         * COORDINATOR_ACK. Only this receipt lets the Relay stop re-delivering the final ACK.
         * Older peers treat it as an unknown message type and ignore it.
         */
        const val TYPE_COORDINATOR_ACK_RECEIPT = "COORDINATOR_ACK_RECEIPT"

        fun fromJson(jsonStr: String): SethuMessage? {
            return try {
                val obj = JSONObject(jsonStr)
                SethuMessage(
                    version = obj.optInt("version", CURRENT_VERSION),
                    messageId = obj.optString("messageId", UUID.randomUUID().toString()),
                    senderDeviceName = obj.optString("senderDeviceName", "Unknown"),
                    timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                    messageType = obj.optString("messageType", TYPE_TEXT),
                    payload = obj.optString("payload", "")
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    fun toJsonString(): String {
        val obj = JSONObject()
        obj.put("version", version)
        obj.put("messageId", messageId)
        obj.put("senderDeviceName", senderDeviceName)
        obj.put("timestamp", timestamp)
        obj.put("messageType", messageType)
        obj.put("payload", payload)
        return obj.toString()
    }
}
