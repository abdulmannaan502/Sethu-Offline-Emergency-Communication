package com.sethu.emergency.comm.bluetooth

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A paired (bonded) peer as seen by the Sethu connection engine.
 * [looksLikePhone] is a ranking hint only; it never excludes a device.
 */
data class PeerDevice(
    val address: String,
    val name: String,
    val looksLikePhone: Boolean = true
)

/** Platform-neutral RFCOMM byte channel. All methods may block and must be called off the main thread. */
interface RfcommChannel : Closeable {
    val remoteAddress: String
    val remoteName: String?
    val isOpen: Boolean
    val input: InputStream
    val output: OutputStream
}

/** Platform-neutral RFCOMM listening endpoint. */
interface RfcommListener : Closeable {
    /** Blocks until a peer connects. Throws [IOException] when closed or on failure. */
    @Throws(IOException::class)
    fun accept(): RfcommChannel
}

/**
 * Seam between the Sethu transport and the Android Bluetooth stack. The production
 * implementation is [AndroidRfcommAdapter]; unit tests substitute an in-memory fake so
 * the real socket lifecycle code runs on the JVM.
 */
interface RfcommAdapter {
    val isSupported: Boolean

    /** Whether the radio is currently on. */
    fun isEnabled(): Boolean

    /** Whether the runtime permissions needed to listen/connect/list bonded devices are granted. */
    fun hasPermission(): Boolean

    /** Bonded devices. Throws [SecurityException] when permission is missing (never returns a fake empty list). */
    @Throws(SecurityException::class)
    fun bondedDevices(): List<PeerDevice>

    @Throws(IOException::class, SecurityException::class)
    fun listen(): RfcommListener

    /** Blocking outbound connect. Implementations must not hang forever; callers also run a watchdog. */
    @Throws(IOException::class, SecurityException::class)
    fun connect(address: String): RfcommChannel

    /** Aborts an in-flight [connect] from another thread; the blocked call must then fail promptly. */
    fun abortConnect()

    fun cancelDiscovery()

    fun localName(): String
}
