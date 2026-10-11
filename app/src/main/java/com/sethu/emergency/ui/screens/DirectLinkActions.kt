package com.sethu.emergency.ui.screens

import com.sethu.emergency.comm.ServerStartResult
import com.sethu.emergency.comm.bluetooth.BluetoothRfcommTransport
import com.sethu.emergency.comm.bluetooth.PeerDevice

/**
 * What the Direct Device Link buttons do, separated from Compose so the exact startup/connect operations can be
 * unit-tested. Both actions run only because the user tapped; nothing else in the app calls them.
 */
object DirectLinkActions {

    /**
     * "Start Server" tap. Returns true when runtime permissions must be requested first (the screen then
     * launches the permission dialog and repeats this request once granted). Otherwise the transport's real
     * startup operation has been invoked; its outcome is published on [BluetoothRfcommTransport.serverState].
     */
    fun onStartServerTapped(hasPermissions: Boolean, transport: BluetoothRfcommTransport): Boolean {
        if (!hasPermissions) return true
        return transport.startServer() == ServerStartResult.PERMISSION_MISSING
    }

    /** "Stop Server" tap: stops listening only; an existing link is left alone. */
    fun onStopServerTapped(transport: BluetoothRfcommTransport) = transport.stopServer()

    /** Paired-device tap = the one and only way an outbound RFCOMM connection starts. Returns true if permission is needed. */
    fun onPairedDeviceTapped(hasPermissions: Boolean, transport: BluetoothRfcommTransport, device: PeerDevice): Boolean {
        if (!hasPermissions) return true
        transport.connectToDevice(device.address, device.name.ifBlank { device.address })
        return false
    }
}
