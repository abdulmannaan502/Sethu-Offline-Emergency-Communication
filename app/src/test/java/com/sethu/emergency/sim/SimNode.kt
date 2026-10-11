package com.sethu.emergency.sim

import com.sethu.emergency.comm.bluetooth.BluetoothRfcommTransport
import com.sethu.emergency.comm.coordinator.CoordinatorReportStore
import com.sethu.emergency.comm.forwarding.ForwardingConfig
import com.sethu.emergency.comm.forwarding.StoreAndForwardController
import com.sethu.emergency.comm.model.SosReport
import com.sethu.emergency.comm.relay.RelayReportStore
import com.sethu.emergency.comm.sender.SenderReportStore
import com.sethu.emergency.ui.screens.DeviceRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File

/** Short, deterministic timings so simulated scenarios finish in well under a second each. */
fun fastConfig() = ForwardingConfig(
    cyclePollIntervalMs = 60,
    ackTimeoutMs = 1200,
    maxAttemptsPerLink = 3
)

/**
 * One simulated phone: fake Bluetooth adapter + real transport + real stores + real controller.
 * [restart] models process death and recreation (new transport/controller, same on-disk stores).
 */
class SimNode(
    val net: FakeNetwork,
    val name: String,
    val address: String,
    val role: DeviceRole,
    val dir: File,
    baseConfig: ForwardingConfig = fastConfig()
) {
    private val config = baseConfig

    val adapter: FakeAdapter = net.newAdapter(address, name)

    private lateinit var scope: CoroutineScope
    lateinit var transport: BluetoothRfcommTransport
    lateinit var senderStore: SenderReportStore
    lateinit var relayStore: RelayReportStore
    lateinit var coordinatorStore: CoordinatorReportStore
    lateinit var controller: StoreAndForwardController

    init {
        boot()
    }

    private fun boot() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        transport = BluetoothRfcommTransport(
            context = null,
            scope = scope,
            adapter = adapter,
            connectTimeoutMs = 1500,
            listenRetryDelayMs = 40
        )
        senderStore = SenderReportStore(File(dir, "sender").also { it.mkdirs() })
        relayStore = RelayReportStore(File(dir, "relay").also { it.mkdirs() })
        coordinatorStore = CoordinatorReportStore(File(dir, "coord").also { it.mkdirs() })
        controller = StoreAndForwardController(
            context = null,
            transport = transport,
            senderStore = senderStore,
            relayStore = relayStore,
            coordinatorStore = coordinatorStore,
            scope = scope,
            config = config
        )
        controller.setLocalRole(role)
    }

    /** App start: runs the delivery worker only. Nothing listens and nothing dials until the user acts. */
    fun start() {
        controller.start()
    }

    /** The user's "Start Server" tap. */
    fun startServer() = transport.startServer()

    /** The user's tap on a paired device in Direct Device Link; waits until the link is Connected AND role-announced. */
    fun connectTo(other: SimNode, timeoutMs: Long = 5000) {
        transport.connectToDevice(other.address, other.name)
        eventually(timeoutMs, "${name} linked to ${other.name}") {
            (transport.connectionState.value as? com.sethu.emergency.comm.ConnectionState.Connected)
                ?.remoteDeviceAddress == other.address && transport.remotePeerRole.value != null
        }
    }

    /** Process death: everything in memory vanishes, sockets close; only files on disk remain. */
    fun kill() {
        controller.stop()
        transport.close()
        scope.cancel()
        adapter.radioOff()
        adapter.radioOn()
    }

    fun restart() {
        kill()
        boot()
        start()
    }

    fun newReport(id: String, name: String = this.name, notes: String = "test"): SosReport = SosReport(
        reportId = id,
        senderDeviceName = name,
        category = "Medical",
        peopleCount = "2",
        notes = notes,
        latitude = 12.97,
        longitude = 77.59,
        accuracyMeters = 12f,
        locationTimestamp = System.currentTimeMillis(),
        locationStatus = SosReport.LOCATION_STATUS_AVAILABLE
    )
}

/** Bounded wait for an asynchronous condition (test-side only; production code has no sleeps-as-sync). */
fun eventually(timeoutMs: Long = 8000, message: String = "condition", cond: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (cond()) return
        Thread.sleep(10)
    }
    if (!cond()) throw AssertionError("Timed out after ${timeoutMs}ms waiting for $message")
}

/** Asserts a condition STAYS true for [durationMs] (e.g. "no duplicate record ever appears"). */
fun staysTrue(durationMs: Long = 400, message: String = "invariant", cond: () -> Boolean) {
    val end = System.currentTimeMillis() + durationMs
    while (System.currentTimeMillis() < end) {
        if (!cond()) throw AssertionError("Invariant violated: $message")
        Thread.sleep(10)
    }
}
