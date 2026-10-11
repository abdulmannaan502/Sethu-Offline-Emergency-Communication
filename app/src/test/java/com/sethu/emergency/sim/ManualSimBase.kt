package com.sethu.emergency.sim

import com.sethu.emergency.comm.ConnectionState
import com.sethu.emergency.comm.ServerState
import com.sethu.emergency.comm.coordinator.CoordinatorReportStore
import com.sethu.emergency.comm.forwarding.ForwardingConfig
import com.sethu.emergency.comm.relay.RelayReportStore
import com.sethu.emergency.comm.sender.SenderReportStore
import com.sethu.emergency.ui.screens.DeviceRole
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared harness for the SIMULATED manual-connection tests. Phones are started ("app open") but nothing listens and
 * nothing dials until a test performs the same user actions as the app: [SimNode.startServer] and [SimNode.connectTo].
 */
abstract class ManualSimBase {

    @get:Rule val tmp = TemporaryFolder()

    protected val IQOO = "11:11:11:11:11:11"
    protected val VIVO = "22:22:22:22:22:22"
    protected val MOTO = "33:33:33:33:33:33"

    data class Frame(val seq: Int, val from: String, val to: String, val type: String, val payload: String)

    protected lateinit var net: FakeNetwork
    protected val nodes = mutableListOf<SimNode>()
    protected val log = CopyOnWriteArrayList<Frame>()
    protected val seq = AtomicInteger()
    protected val rules = CopyOnWriteArrayList<(Frame) -> FakeNetwork.Action?>()

    @Before
    fun setUp() {
        net = FakeNetwork()
        net.interceptor = { from, to, msg ->
            val f = Frame(seq.incrementAndGet(), from, to, msg?.messageType ?: "?", msg?.payload ?: "")
            log.add(f)
            rules.firstNotNullOfOrNull { it(f) } ?: FakeNetwork.Action.DELIVER
        }
    }

    @After
    fun tearDown() {
        nodes.forEach { it.kill() }
    }

    protected fun phone(name: String, addr: String, role: DeviceRole, cfg: ForwardingConfig = fastConfig()): SimNode {
        val n = SimNode(net, name, addr, role, tmp.newFolder(name.replace(" ", "_")), cfg)
        nodes.add(n)
        n.start() // "open the app and pick the role"
        return n
    }

    /** iQOO is paired with BOTH other phones, Vivo with both too: auto-dialling would have plenty of targets. */
    protected fun threePhones(cfg: ForwardingConfig = fastConfig()): Triple<SimNode, SimNode, SimNode> {
        val iqoo = phone("iQOO 15", IQOO, DeviceRole.SOS_SENDER, cfg)
        val vivo = phone("Vivo Y15", VIVO, DeviceRole.RELAY_DEVICE, cfg)
        val moto = phone("Motorola Edge 30 Pro", MOTO, DeviceRole.COORDINATOR, cfg)
        listOf(iqoo, vivo, moto).forEach { a -> listOf(iqoo, vivo, moto).filter { it !== a }.forEach { a.adapter.bond(it.adapter) } }
        return Triple(iqoo, vivo, moto)
    }

    protected fun connected(n: SimNode, to: String) =
        (n.transport.connectionState.value as? ConnectionState.Connected)?.remoteDeviceAddress == to

    protected fun framesOf(type: String, from: String? = null, to: String? = null) =
        log.filter { it.type == type && (from == null || it.from == from) && (to == null || it.to == to) }

    // Durability assertions read a SNAPSHOT COPY of the node's on-disk file (never the live file).
    protected fun snapshot(n: SimNode, sub: String): File {
        val src = File(File(n.dir, sub), "sethu_${if (sub == "coord") "coordinator" else sub}_store.json")
        val dst = tmp.newFolder()
        repeat(100) {
            try { if (src.exists()) src.copyTo(File(dst, src.name), overwrite = true); return dst } catch (_: Exception) { Thread.sleep(5) }
        }
        return dst
    }
    protected fun diskRelay(n: SimNode) = RelayReportStore(snapshot(n, "relay"))
    protected fun diskCoord(n: SimNode) = CoordinatorReportStore(snapshot(n, "coord"))
    protected fun diskSender(n: SimNode) = SenderReportStore(snapshot(n, "sender"))

    protected fun diag() = buildString {
        appendLine()
        nodes.forEach {
            appendLine("[${it.name}] conn=${it.transport.connectionState.value} server=${it.transport.serverState.value} " +
                "status=${it.controller.status.value.kind} relay=${it.relayStore.getAllReports().map { r -> r.report.reportId + ":" + r.deliveryState }} " +
                "coord=${it.coordinatorStore.getAllReports().map { r -> r.reportId }} dials=${it.adapter.connectAttempts.get()}")
        }
        log.takeLast(20).forEach { appendLine("  #${it.seq} ${it.from.take(2)}->${it.to.take(2)} ${it.type} ${it.payload.take(16)}") }
    }

    protected fun waitFor(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        try { eventually(timeoutMs, what, cond) } catch (e: AssertionError) { throw AssertionError(e.message + diag()) }
    }

    /** Fault that fires exactly once for the first frame matching [pred]. */
    protected fun once(action: FakeNetwork.Action, pred: (Frame) -> Boolean): AtomicBoolean {
        val fired = AtomicBoolean(false)
        rules.add { f -> if (pred(f) && fired.compareAndSet(false, true)) action else null }
        return fired
    }

    protected fun startServerAndWait(n: SimNode) {
        n.startServer()
        waitFor("${n.name} listening") { n.transport.serverState.value is ServerState.Listening }
    }

    /** User ends the link (Disconnect button) and both sides observe it. */
    protected fun linkDown(a: SimNode, b: SimNode) {
        a.transport.disconnect()
        waitFor("link ${a.name}<->${b.name} down") { !connected(a, b.address) && !connected(b, a.address) }
    }
}
