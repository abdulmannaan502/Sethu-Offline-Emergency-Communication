package com.sethu.emergency.sim

import com.sethu.emergency.comm.ConnectionState
import com.sethu.emergency.comm.RoleCompatibility
import com.sethu.emergency.comm.ServerStartResult
import com.sethu.emergency.comm.ServerState
import com.sethu.emergency.comm.bluetooth.BluetoothRfcommTransport
import com.sethu.emergency.comm.bluetooth.PeerDevice
import com.sethu.emergency.comm.model.SethuMessage
import com.sethu.emergency.ui.screens.DeviceRole
import com.sethu.emergency.ui.screens.DirectLinkActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * SIMULATED tests (fake RFCOMM sockets; real transport/controller code) for the Start Server and Direct Device
 * Link behaviour. They prove the application logic, not the Android Bluetooth stack on the physical phones.
 */
class ServerAndDirectLinkTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var net: FakeNetwork
    private lateinit var a: FakeAdapter
    private lateinit var b: FakeAdapter
    private lateinit var scopeA: CoroutineScope
    private lateinit var scopeB: CoroutineScope
    private lateinit var tA: BluetoothRfcommTransport
    private lateinit var tB: BluetoothRfcommTransport

    @Before
    fun setUp() {
        net = FakeNetwork()
        a = net.newAdapter("AA:AA:AA:AA:AA:01", "Alpha")
        b = net.newAdapter("BB:BB:BB:BB:BB:02", "Bravo")
        scopeA = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scopeB = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        tA = BluetoothRfcommTransport(null, scopeA, a, connectTimeoutMs = 600, listenRetryDelayMs = 30)
        tB = BluetoothRfcommTransport(null, scopeB, b, connectTimeoutMs = 600, listenRetryDelayMs = 30)
        tA.localRoleName = "SOS_SENDER"
        tB.localRoleName = "RELAY_DEVICE"
    }

    @After
    fun tearDown() {
        tA.close(); tB.close(); scopeA.cancel(); scopeB.cancel()
    }

    // 1. Start Server button invokes the correct startup operation
    @Test
    fun startServerTapInvokesTransportStartupAndReachesListening() {
        assertEquals("nothing listens before the tap", 0, b.listenCalls.get())
        assertEquals(ServerState.Stopped, tB.serverState.value)

        val needsPermissionPrompt = DirectLinkActions.onStartServerTapped(hasPermissions = true, transport = tB)

        assertFalse(needsPermissionPrompt)
        // 2. startup success produces a real listening state, backed by a real listener
        eventually(message = "Listening") { tB.serverState.value is ServerState.Listening }
        assertTrue(tB.connectionState.value is ConnectionState.Listening)
        assertEquals(1, b.activeListenerCount)
    }

    @Test
    fun startServerTapWithoutPermissionsAsksForPermissionAndStartsNothing() {
        assertTrue(DirectLinkActions.onStartServerTapped(hasPermissions = false, transport = tB))
        assertEquals(0, b.listenCalls.get())
        assertEquals(ServerState.Stopped, tB.serverState.value)
    }

    // 3. Listener startup failure produces a visible error state
    @Test
    fun listenerStartupFailureIsVisibleAndNotRetriedInATightLoop() {
        b.listenFailure = IOException("SDP record rejected")
        assertEquals(ServerStartResult.STARTED, tB.startServer())

        eventually(message = "visible failure") { tB.serverState.value is ServerState.Failed }
        val failed = tB.serverState.value as ServerState.Failed
        assertTrue(failed.reason, failed.reason.contains("SDP record rejected"))
        assertTrue(tB.connectionState.value is ConnectionState.Failed)
        val callsAfterFailure = b.listenCalls.get()
        staysTrue(400, "no retry storm after listen failure") { b.listenCalls.get() == callsAfterFailure }

        // The user can tap again once the cause is gone.
        b.listenFailure = null
        tB.startServer()
        eventually(message = "listening after retry tap") { tB.serverState.value is ServerState.Listening }
    }

    // 4. Repeated Start Server taps do not create duplicate listeners
    @Test
    fun repeatedStartServerTapsCreateExactlyOneListener() {
        val results = (1..6).map { tB.startServer() }
        assertEquals(ServerStartResult.STARTED, results.first())
        assertTrue(results.drop(1).all { it == ServerStartResult.ALREADY_RUNNING || it == ServerStartResult.STARTED })
        eventually(message = "Listening") { tB.serverState.value is ServerState.Listening }
        Thread.sleep(200)
        assertEquals(1, b.listenCalls.get())
        assertEquals(1, b.maxConcurrentListeners.get())
        assertEquals(1, b.activeListenerCount)
    }

    // 5. Missing Bluetooth permissions are handled explicitly
    @Test
    fun missingPermissionIsExplicit() {
        b.permission = false
        assertEquals(ServerStartResult.PERMISSION_MISSING, tB.startServer())
        assertEquals(ServerState.Failed(BluetoothRfcommTransport.MSG_PERMISSION_REQUIRED), tB.serverState.value)
        assertEquals(0, b.listenCalls.get())
    }

    // 6. Bluetooth-disabled state is handled explicitly
    @Test
    fun bluetoothDisabledIsExplicitAndNothingListens() {
        b.enabled = false
        assertEquals(ServerStartResult.BLUETOOTH_DISABLED, tB.startServer())
        assertEquals(ServerState.Failed(BluetoothRfcommTransport.MSG_BLUETOOTH_DISABLED), tB.serverState.value)
        assertEquals(0, b.listenCalls.get())
    }

    @Test
    fun unsupportedBluetoothIsExplicit() {
        b.supported = false
        assertEquals(ServerStartResult.BLUETOOTH_UNSUPPORTED, tB.startServer())
        assertTrue(tB.serverState.value is ServerState.Failed)
    }

    @Test
    fun bluetoothOffThenOnResumesTheServerTheUserStarted() {
        tB.startServer()
        eventually { tB.serverState.value is ServerState.Listening }
        b.radioOff()
        tB.onBluetoothDisabled()
        eventually(message = "failure shown") { tB.serverState.value is ServerState.Failed }
        b.radioOn()
        tB.onBluetoothEnabled()
        eventually(message = "listening again") { tB.serverState.value is ServerState.Listening }
        assertEquals(1, b.activeListenerCount)
    }

    @Test
    fun stopServerClosesTheListenerButNotAnEstablishedLink() = runBlocking {
        tB.startServer()
        eventually { tB.serverState.value is ServerState.Listening }
        assertTrue(tA.connect(b.address, "Bravo"))
        eventually { tB.connectionState.value is ConnectionState.Connected }

        tB.stopServer()
        assertEquals(ServerState.Stopped, tB.serverState.value)
        assertEquals(0, b.activeListenerCount)
        assertTrue("stopping the listener must not drop the live link", tB.connectionState.value is ConnectionState.Connected)
        assertTrue(tA.connectionState.value is ConnectionState.Connected)

        tB.disconnect()
        eventually { tA.connectionState.value !is ConnectionState.Connected }
        Thread.sleep(200)
        assertEquals("a stopped server must not come back by itself", 0, b.activeListenerCount)
        assertEquals(ServerState.Stopped, tB.serverState.value)
    }

    // 7. Direct Device Link requires a user action
    @Test
    fun nothingListensOrDialsUntilTheUserActs() {
        a.bond(b); b.bond(a)
        val node = SimNode(net, "Vivo", "CC:CC:CC:CC:CC:03", DeviceRole.RELAY_DEVICE, tmp.newFolder())
        node.adapter.bond(a)
        try {
            node.start() // "app open": delivery worker only
            staysTrue(500, "no listener, no outbound connect after app start") {
                node.adapter.listenCalls.get() == 0 && node.adapter.connectAttempts.get() == 0
            }
        } finally {
            node.kill()
        }
    }

    @Test
    fun pairedDeviceTapIsTheOnlyThingThatConnects() {
        tB.startServer()
        eventually { tB.serverState.value is ServerState.Listening }
        assertEquals(0, a.connectAttempts.get())

        assertFalse(DirectLinkActions.onPairedDeviceTapped(true, tA, PeerDevice(b.address, "Bravo")))
        eventually(message = "connected") { tA.connectionState.value is ConnectionState.Connected }
        assertEquals(1, a.connectAttempts.get())
        assertEquals("RELAY_DEVICE", runBlocking { tA.awaitPeerRole(1000) })
    }

    @Test
    fun doubleTapOnSamePeerDoesNotStackConnectionAttempts() {
        tB.startServer()
        eventually { tB.serverState.value is ServerState.Listening }
        a.connectDelayMs = 300
        repeat(5) { tA.connectToDevice(b.address, "Bravo") }
        eventually(message = "connected") { tA.connectionState.value is ConnectionState.Connected }
        Thread.sleep(150)
        assertEquals(1, a.connectAttempts.get())
        // Tapping again while connected is a no-op as well.
        tA.connectToDevice(b.address, "Bravo")
        Thread.sleep(150)
        assertEquals(1, a.connectAttempts.get())
    }

    // 8./9./10. role compatibility
    @Test
    fun roleCompatibilityMatrix() {
        val s = DeviceRole.SOS_SENDER.name; val r = DeviceRole.RELAY_DEVICE.name; val c = DeviceRole.COORDINATOR.name
        assertTrue(RoleCompatibility.isCompatible(s, r))
        assertTrue(RoleCompatibility.isCompatible(r, s))
        assertTrue(RoleCompatibility.isCompatible(r, c))
        assertTrue(RoleCompatibility.isCompatible(c, r))
        assertFalse(RoleCompatibility.isCompatible(s, s))
        assertFalse(RoleCompatibility.isCompatible(r, r))
        assertFalse(RoleCompatibility.isCompatible(c, c))
        assertFalse(RoleCompatibility.isCompatible(r, "UNKNOWN"))
    }

    // 11. Relay does not initiate (or keep) links to incompatible roles
    @Test
    fun relayDropsAManualLinkToAnotherRelayAndRefusesToDialItAgain() {
        val vivo = SimNode(net, "Vivo Y15", "22:22:22:22:22:22", DeviceRole.RELAY_DEVICE, tmp.newFolder("v"))
        val other = SimNode(net, "Other Relay", "55:55:55:55:55:55", DeviceRole.RELAY_DEVICE, tmp.newFolder("o"))
        try {
            vivo.start(); other.start()
            other.startServer()
            eventually { other.transport.serverState.value is ServerState.Listening }

            vivo.transport.connectToDevice(other.address, other.name)
            eventually(message = "incompatible link dropped with a reason") {
                vivo.controller.status.value.kind == com.sethu.emergency.comm.forwarding.EngineStatus.Kind.WRONG_PEER_ROLE &&
                    vivo.transport.connectionState.value !is ConnectionState.Connected
            }
            val attempts = vivo.adapter.connectAttempts.get()
            assertEquals(1, attempts)

            // The role is now known: another tap is rejected before any radio activity.
            vivo.transport.connectToDevice(other.address, other.name)
            Thread.sleep(200)
            assertEquals(attempts, vivo.adapter.connectAttempts.get())
            assertTrue(vivo.transport.transferStatus.value is com.sethu.emergency.comm.TransferStatus.Failed)
        } finally {
            vivo.kill(); other.kill()
        }
    }

    // Stage-correct ACKs: a Sender-role peer can never complete a Coordinator-stage wait
    @Test
    fun coordinatorStageAckFromANonCoordinatorPeerNeverCountsAsDelivery() = runBlocking {
        tB.startServer()
        eventually { tB.serverState.value is ServerState.Listening }
        assertTrue(tA.connect(b.address, "Bravo"))
        assertEquals("SOS_SENDER", tB.awaitPeerRole(1000))

        val report = SimNode(net, "x", "EE:EE:EE:EE:EE:05", DeviceRole.SOS_SENDER, tmp.newFolder()).let {
            val r = it.newReport("SOS-WRONG-STAGE"); it.kill(); r
        }
        // A (announced as SOS_SENDER) sends a COORDINATOR_ACK for the report: must be ignored for B's wait.
        tA.sendCoordinatorAck(report.reportId)
        val delivered = tB.sendSosReportAwaitingAck(report, SethuMessage.TYPE_COORDINATOR_ACK, 500)
        assertFalse("ACK from a non-Coordinator peer must not count", delivered)

        // The same peer's Relay-stage ACK type must not satisfy a Coordinator-stage wait either.
        tA.sendSosAck(report.reportId)
        assertFalse(tB.sendSosReportAwaitingAck(report, SethuMessage.TYPE_COORDINATOR_ACK, 300))
    }
}
