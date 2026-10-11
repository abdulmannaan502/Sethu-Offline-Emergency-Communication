package com.sethu.emergency.comm.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import com.sethu.emergency.comm.BluetoothPermissionHelper
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Production [RfcommAdapter] backed by the Android Bluetooth Classic stack. */
@SuppressLint("MissingPermission")
class AndroidRfcommAdapter(private val context: Context) : RfcommAdapter {

    companion object {
        const val SERVICE_NAME = "SethuEmergencyRFCOMM"

        // App-specific service UUID. The generic SPP UUID (00001101-...) is also registered by vendor Bluetooth
        // stacks, so a client's SDP lookup can resolve to the wrong record and fail with "read ret: -1".
        // Both phones run the same build, so both sides use this UUID. (Name kept for existing references.)
        val SPP_UUID: UUID = UUID.fromString("a7c3e5b2-6d4f-4b8a-9e1c-3f2d5a7b9c10")
    }

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    override val isSupported: Boolean get() = adapter != null

    override fun isEnabled(): Boolean = try {
        adapter?.isEnabled == true
    } catch (_: SecurityException) {
        false
    }

    override fun hasPermission(): Boolean = BluetoothPermissionHelper.hasPermissions(context)

    override fun bondedDevices(): List<PeerDevice> {
        val bonded = adapter?.bondedDevices ?: return emptyList()
        return bonded.map { device ->
            val major = try { device.bluetoothClass?.majorDeviceClass } catch (_: Exception) { null }
            val name = try { device.name } catch (_: SecurityException) { null }
            PeerDevice(
                address = device.address,
                name = name?.takeIf { it.isNotBlank() } ?: device.address,
                // Unknown class stays eligible; only clearly non-phone classes are ranked lower.
                looksLikePhone = major == null ||
                    major == BluetoothClass.Device.Major.PHONE ||
                    major == BluetoothClass.Device.Major.UNCATEGORIZED ||
                    major == BluetoothClass.Device.Major.COMPUTER
            )
        }
    }

    override fun listen(): RfcommListener {
        val bt = adapter ?: throw IOException("Bluetooth is not supported on this device")
        val server = try {
            bt.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
        } catch (e: IOException) {
            // Fallback to insecure RFCOMM listener if secure SDP registration fails on vendor stack
            try {
                bt.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
            } catch (_: IOException) {
                throw e
            }
        } ?: throw IOException("Could not initialize RFCOMM server socket")
        return AndroidListener(server)
    }

    override fun connect(address: String): RfcommChannel {
        val bt = adapter ?: throw IOException("Bluetooth is not supported on this device")
        val device = bt.getRemoteDevice(address)
        aborted = false
        var socket: BluetoothSocket? = null
        try {
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            pendingSocket = socket
            socket.connect()
            pendingSocket = null
            return AndroidChannel(socket)
        } catch (first: IOException) {
            try { socket?.close() } catch (_: IOException) {}
            pendingSocket = null
            if (aborted) throw first
            // One bounded retry within this same user-initiated connect: the first SDP lookup often fails with
            // "read ret: -1" while the stack is still warming its service cache, and succeeds immediately after.
            try {
                Thread.sleep(700)
                if (!aborted) {
                    var retry: BluetoothSocket? = null
                    try {
                        retry = device.createRfcommSocketToServiceRecord(SPP_UUID)
                        pendingSocket = retry
                        retry.connect()
                        pendingSocket = null
                        return AndroidChannel(retry)
                    } catch (_: IOException) {
                        try { retry?.close() } catch (_: IOException) {}
                        pendingSocket = null
                    }
                    if (!aborted) {
                        try {
                            val insecure = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                            pendingSocket = insecure
                            insecure.connect()
                            pendingSocket = null
                            return AndroidChannel(insecure)
                        } catch (_: IOException) {
                            try { pendingSocket?.close() } catch (_: IOException) {}
                            pendingSocket = null
                        }
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (aborted) throw first
            // Reflection fallback for vendor stacks that reject the SDP-based lookup (e.g. older Vivo builds).
            // It dials RFCOMM channel 1 directly, which only reaches Sethu if the peer's listener happens to hold
            // channel 1.
            try {
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                val fallback = m.invoke(device, 1) as? BluetoothSocket
                    ?: throw IOException("Fallback RFCOMM socket unavailable")
                pendingSocket = fallback
                try {
                    fallback.connect()
                } catch (e: IOException) {
                    try { fallback.close() } catch (_: IOException) {}
                    throw e
                } finally {
                    pendingSocket = null
                }
                return AndroidChannel(fallback)
            } catch (_: Exception) {
                throw first
            }
        }
    }

    @Volatile private var pendingSocket: BluetoothSocket? = null
    @Volatile private var aborted = false

    override fun abortConnect() {
        aborted = true
        try { pendingSocket?.close() } catch (_: IOException) {}
    }

    override fun cancelDiscovery() {
        try { adapter?.cancelDiscovery() } catch (_: SecurityException) {}
    }

    override fun localName(): String = try {
        adapter?.name?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Sethu Device"
    } catch (_: Exception) {
        Build.MODEL ?: "Sethu Device"
    }

    private class AndroidListener(private val server: BluetoothServerSocket) : RfcommListener {
        override fun accept(): RfcommChannel = AndroidChannel(server.accept())
        override fun close() {
            try { server.close() } catch (_: IOException) {}
        }
    }

    private class AndroidChannel(private val socket: BluetoothSocket) : RfcommChannel {
        override val remoteAddress: String
            get() = try { socket.remoteDevice?.address ?: "" } catch (_: Exception) { "" }
        override val remoteName: String?
            get() = try { socket.remoteDevice?.name } catch (_: Exception) { null }
        override val isOpen: Boolean get() = socket.isConnected
        override val input: InputStream get() = socket.inputStream
        override val output: OutputStream get() = socket.outputStream
        override fun close() {
            try { socket.close() } catch (_: IOException) {}
        }
    }
}
