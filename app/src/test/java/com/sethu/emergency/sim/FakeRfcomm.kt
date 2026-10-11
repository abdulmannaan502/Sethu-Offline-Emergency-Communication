package com.sethu.emergency.sim

import com.sethu.emergency.comm.bluetooth.PeerDevice
import com.sethu.emergency.comm.bluetooth.RfcommAdapter
import com.sethu.emergency.comm.bluetooth.RfcommChannel
import com.sethu.emergency.comm.bluetooth.RfcommListener
import com.sethu.emergency.comm.model.SethuMessage
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory Bluetooth Classic RFCOMM simulation.
 *
 * SIMULATION ONLY: it exercises the real Sethu transport/controller code (framing, socket lifecycle,
 * ownership, handshake, store-and-forward) against deterministic fake sockets. It is NOT evidence of
 * real Bluetooth stack, pairing, SDP or radio behaviour.
 */
class FakeNetwork {
    val adapters = ConcurrentHashMap<String, FakeAdapter>()

    /** Max bytes returned per read(); small values force partial reads. */
    @Volatile var readChunk: Int = 4096

    enum class Action { DELIVER, DROP, DUPLICATE, SEVER_BEFORE, SEVER_AFTER }

    /** Per-frame fault hook: (fromAddress, toAddress, parsed message) -> action. */
    @Volatile var interceptor: (String, String, SethuMessage?) -> Action = { _, _, _ -> Action.DELIVER }

    fun newAdapter(address: String, name: String): FakeAdapter {
        val a = FakeAdapter(this, address, name)
        adapters[address] = a
        return a
    }
}

internal class FakePipe(private val net: FakeNetwork) {
    private val q = LinkedBlockingQueue<ByteArray>()
    private val eof = ByteArray(0)
    @Volatile var closed = false

    fun put(bytes: ByteArray) {
        if (!closed) q.put(bytes)
    }

    fun closeAndSignal() {
        closed = true
        q.put(eof)
    }

    val input = object : InputStream() {
        private var cur: ByteArray? = null
        private var pos = 0
        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (cur == null || pos >= cur!!.size) {
                val next = q.take()
                if (next === eof) {
                    q.put(eof)
                    return -1
                }
                cur = next
                pos = 0
            }
            val c = cur!!
            val n = minOf(len, c.size - pos, net.readChunk)
            System.arraycopy(c, pos, b, off, n)
            pos += n
            return n
        }
    }
}

internal class FakeChannel(
    private val net: FakeNetwork,
    private val localAddress: String,
    override val remoteAddress: String,
    override val remoteName: String?,
    private val incoming: FakePipe,
    private val outgoing: FakePipe
) : RfcommChannel {
    @Volatile private var open = true
    override val isOpen: Boolean get() = open
    override val input: InputStream get() = incoming.input
    val writes = AtomicInteger()

    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()))
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (!open || outgoing.closed) throw IOException("Broken pipe")
            val bytes = b.copyOfRange(off, off + len)
            writes.incrementAndGet()
            val msg = SethuMessage.fromJson(String(bytes, Charsets.UTF_8).trim())
            when (net.interceptor(localAddress, remoteAddress, msg)) {
                FakeNetwork.Action.DELIVER -> outgoing.put(bytes)
                FakeNetwork.Action.DROP -> Unit
                FakeNetwork.Action.DUPLICATE -> { outgoing.put(bytes); outgoing.put(bytes) }
                FakeNetwork.Action.SEVER_BEFORE -> { close(); throw IOException("Link lost") }
                FakeNetwork.Action.SEVER_AFTER -> { outgoing.put(bytes); close() }
            }
        }

        override fun flush() {}
    }

    override fun close() {
        if (!open) return
        open = false
        outgoing.closeAndSignal() // peer reads remaining bytes, then EOF
        incoming.closeAndSignal() // our own blocked reader ends
    }
}

class FakeListener(private val owner: FakeAdapter) : RfcommListener {
    private val q = LinkedBlockingQueue<Any>()
    private val closedMarker = Any()
    @Volatile var closed = false

    internal fun offer(channel: FakeChannel) {
        if (closed) throw IOException("Connection refused")
        q.put(channel)
    }

    override fun accept(): RfcommChannel {
        val item = q.take()
        if (item === closedMarker) {
            q.put(closedMarker)
            throw IOException("Listener closed")
        }
        return item as FakeChannel
    }

    override fun close() {
        if (closed) return
        closed = true
        owner.onListenerClosed(this)
        q.put(closedMarker)
    }
}

class FakeAdapter(
    private val net: FakeNetwork,
    val address: String,
    val name: String
) : RfcommAdapter {
    @Volatile var enabled = true
    @Volatile var permission = true
    @Volatile var supported = true
    @Volatile var connectDelayMs = 0L
    /** When true, connect() blocks until abortConnect() (models a peer that never answers). */
    @Volatile var connectHangs = false
    /** When set, listen() throws it (models the stack refusing to open the RFCOMM service record). */
    @Volatile var listenFailure: IOException? = null

    val bonded = CopyOnWriteArrayList<PeerDevice>()
    val connectAttempts = AtomicInteger()
    val listenCalls = AtomicInteger()
    val maxConcurrentListeners = AtomicInteger()
    private val openListeners = CopyOnWriteArrayList<FakeListener>()
    private val openChannels = CopyOnWriteArrayList<FakeChannel>()
    @Volatile private var abortLatch: CountDownLatch? = null

    @Volatile var listener: FakeListener? = null
        private set

    fun bond(other: FakeAdapter, looksLikePhone: Boolean = true) {
        bonded.add(PeerDevice(other.address, other.name, looksLikePhone))
    }

    val activeListenerCount: Int get() = openListeners.size

    internal fun onListenerClosed(l: FakeListener) {
        openListeners.remove(l)
        if (listener === l) listener = null
    }

    override val isSupported: Boolean get() = supported
    override fun isEnabled(): Boolean = enabled
    override fun hasPermission(): Boolean = permission

    override fun bondedDevices(): List<PeerDevice> {
        if (!permission) throw SecurityException("BLUETOOTH_CONNECT not granted")
        return bonded.toList()
    }

    override fun listen(): RfcommListener {
        if (!permission) throw SecurityException("BLUETOOTH_CONNECT not granted")
        if (!enabled) throw IOException("Bluetooth off")
        listenCalls.incrementAndGet()
        listenFailure?.let { throw it }
        val l = FakeListener(this)
        openListeners.add(l)
        maxConcurrentListeners.updateAndGet { maxOf(it, openListeners.size) }
        listener = l
        return l
    }

    override fun connect(address: String): RfcommChannel {
        if (!permission) throw SecurityException("BLUETOOTH_CONNECT not granted")
        connectAttempts.incrementAndGet()
        val latch = CountDownLatch(1)
        abortLatch = latch
        if (connectHangs) {
            latch.await()
            throw IOException("Connect aborted")
        }
        if (connectDelayMs > 0 && latch.await(connectDelayMs, TimeUnit.MILLISECONDS)) {
            throw IOException("Connect aborted")
        }
        if (!enabled) throw IOException("Bluetooth off")
        val target = net.adapters[address] ?: throw IOException("Host unreachable")
        if (!target.enabled) throw IOException("Host unreachable")
        val l = target.listener ?: throw IOException("Connection refused")
        val aToB = FakePipe(net)
        val bToA = FakePipe(net)
        val mine = FakeChannel(net, this.address, address, target.name, incoming = bToA, outgoing = aToB)
        val theirs = FakeChannel(net, address, this.address, this.name, incoming = aToB, outgoing = bToA)
        l.offer(theirs)
        openChannels.add(mine)
        target.openChannels.add(theirs)
        return mine
    }

    override fun abortConnect() {
        abortLatch?.countDown()
    }

    override fun cancelDiscovery() {}

    override fun localName(): String = name

    /** Models the Bluetooth radio switching off: listeners and sockets die. */
    fun radioOff() {
        enabled = false
        openListeners.toList().forEach { it.close() }
        openChannels.toList().forEach { it.close() }
    }

    fun radioOn() {
        enabled = true
    }
}
