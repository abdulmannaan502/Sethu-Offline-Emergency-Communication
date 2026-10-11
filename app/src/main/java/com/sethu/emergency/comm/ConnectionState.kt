package com.sethu.emergency.comm

/**
 * Honest connection states for peer-to-peer transport.
 */
sealed class ConnectionState {
    object NotConnected : ConnectionState() {
        override fun toString(): String = "Not connected"
    }

    data class Listening(val serviceName: String) : ConnectionState() {
        override fun toString(): String = "Waiting for incoming connection (Hosting Server)..."
    }

    data class Connecting(val targetDeviceName: String) : ConnectionState() {
        override fun toString(): String = "Connecting to $targetDeviceName..."
    }

    data class Connected(
        val remoteDeviceName: String,
        val remoteDeviceAddress: String,
        val isServer: Boolean
    ) : ConnectionState() {
        override fun toString(): String = "Connected to $remoteDeviceName"
    }

    data class Failed(val reason: String) : ConnectionState() {
        override fun toString(): String = "Failed: $reason"
    }
}

/**
 * Honest transfer statuses for message delivery tracking.
 */
sealed class TransferStatus {
    object Idle : TransferStatus()

    data class Sending(val messageId: String) : TransferStatus() {
        override fun toString(): String = "Sending message..."
    }

    data class MessageSent(val messageId: String, val summary: String) : TransferStatus() {
        override fun toString(): String = "Message sent ($summary)"
    }

    data class MessageReceived(val messageId: String, val fromDevice: String) : TransferStatus() {
        override fun toString(): String = "Message received from $fromDevice"
    }

    data class ReportAcknowledged(val reportId: String, val fromDevice: String) : TransferStatus() {
        override fun toString(): String = "Confirmed delivery by $fromDevice"
    }

    data class Failed(val error: String) : TransferStatus() {
        override fun toString(): String = "Transfer failed: $error"
    }
}

/**
 * State of the RFCOMM listener ("Start Server"), reported separately from the link state so the UI can show
 * the real outcome of the user's tap instead of a silent no-op.
 */
sealed class ServerState {
    object Stopped : ServerState()
    object Starting : ServerState()
    data class Listening(val serviceName: String) : ServerState()

    /** Server was started by the user, but a link (or an outbound attempt) currently owns the radio. */
    object Busy : ServerState()
    data class Failed(val reason: String) : ServerState()

    val isActive: Boolean get() = this is Starting || this is Listening || this is Busy
}

/** Immediate, synchronous outcome of a Start Server request (the asynchronous outcome is in [ServerState]). */
enum class ServerStartResult {
    STARTED, ALREADY_RUNNING, BLUETOOTH_UNSUPPORTED, BLUETOOTH_DISABLED, PERMISSION_MISSING
}

/**
 * Which Sethu roles may be linked. Sender-Relay and Relay-Coordinator are the normal hops; Sender-Coordinator is
 * the direct path. Same-role pairs are never useful and are rejected.
 */
object RoleCompatibility {
    private const val SENDER = "SOS_SENDER"
    private const val RELAY = "RELAY_DEVICE"
    private const val COORDINATOR = "COORDINATOR"

    fun isCompatible(localRole: String, peerRole: String): Boolean = when (localRole) {
        SENDER -> peerRole == RELAY || peerRole == COORDINATOR
        RELAY -> peerRole == SENDER || peerRole == COORDINATOR
        COORDINATOR -> peerRole == SENDER || peerRole == RELAY
        else -> false
    }
}
