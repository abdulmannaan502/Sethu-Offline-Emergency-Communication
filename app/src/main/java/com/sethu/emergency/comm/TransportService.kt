package com.sethu.emergency.comm

import com.sethu.emergency.comm.model.SethuMessage
import kotlinx.coroutines.flow.StateFlow

/**
 * Reusable communication interface that future phases can extend
 * without modifying UI presentation or message structures.
 */
interface TransportService {
    val connectionState: StateFlow<ConnectionState>
    val transferStatus: StateFlow<TransferStatus>
    val receivedMessages: StateFlow<List<SethuMessage>>
    val sentMessages: StateFlow<List<SethuMessage>>
    val acknowledgedReportIds: StateFlow<Set<String>>

    /**
     * Start hosting an RFCOMM server listening for peer connections.
     */
    fun startHostingServer()

    /**
     * Connect to a specific remote device.
     */
    fun connectToDevice(deviceAddress: String, deviceName: String)

    /**
     * Send a message through the active socket.
     * Only reports successful delivery after socket flush succeeds.
     */
    fun sendMessage(
        payload: String,
        messageType: String = SethuMessage.TYPE_TEST_PING
    ): Boolean

    /**
     * Send a structured emergency SOS report.
     * Preserves report ID and timestamp.
     */
    fun sendSosReport(report: com.sethu.emergency.comm.model.SosReport): Boolean

    /**
     * Disconnect active socket and close listening server.
     */
    fun disconnect()

    /**
     * Reset transfer status back to Idle.
     */
    fun clearTransferStatus()
}
