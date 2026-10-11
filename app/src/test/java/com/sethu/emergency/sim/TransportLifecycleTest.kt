package com.sethu.emergency.sim

import com.sethu.emergency.comm.ConnectionState
import com.sethu.emergency.comm.bluetooth.BluetoothRfcommTransport
import com.sethu.emergency.comm.model.SethuMessage
import com.sethu.emergency.comm.model.SosReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SIMULATED INTEGRATION TESTS of the real BluetoothRfcommTransport over in-memory fake RFCOMM sockets.
 * They verify socket lifecycle, ownership, handshake and framing logic; they do not prove real Bluetooth stack
 * behaviour on any physical phone.
 */
class TransportLifecycleTest {

    private lateinit var net: FakeNetwork
    private lateinit var scopeA: CoroutineScope
    private lateinit var scopeB: CoroutineScope
    private lateinit var a: FakeAdapter
    private lateinit var b: FakeAdapter
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
        tA.close(); tB.close()
        scopeA.cancel(); scopeB.cancel()
    }

    private fun connected(t: BluetoothRfcommTransport) = t.connectionState.value is ConnectionState.Connected

    // ---------------------------------------------------------------- server lifecycle

    @Test
    fun serverStartupIsIdempotent_singleListener() {
        repeat(5) { tB.startHostingServer() }
        eventually(message = "listening") { tB.connectionState.value is ConnectionState.Listening }
        Thread.sleep(150)
        assertEquals("only one server loop / listener may exist", 1, b.maxConcurrentListeners.get())
        assertEquals(1, b.activeListenerCount)
    }

    @Test
    fun inboundAndOutboundConnectionsReachConnectedWithRoleHandshake() = runBlocking {
        tB.startAutoServerListening()
        eventually(message = "B listening") { tB.connectionState.value is ConnectionState.Listening }

        assertTrue(tA.connect(b.address, "Bravo"))
        eventually(message = "B connected") { connected(tB) }

        assertEquals("RELAY_DEVICE", tA.awaitPeerRole(1000))
        assertEquals("SOS_SENDER", tB.awaitPeerRole(1000))
        val st = tB.connectionState.value as ConnectionState.Connected
        assertTrue(st.isServer)
        assertEquals(a.address, st.remoteDeviceAddress)
        assertFalse((tA.connectionState.value as ConnectionState.Connected).isServer)
    }

    /** Regression for root cause #2: controller-style releaseConnection() used to leave the device deaf. */
    @Test
    fun serverResumesListeningAfterEverySessionEnds() = runBlocking {
        tB.startAutoServerListening()
        repeat(3) { round ->
            eventually(message = "B listening before round $round") { tB.connectionState.value is ConnectionState.Listening }
            assertTrue("round $round connect", tA.connect(b.address, "Bravo"))
            eventually(message = "B connected round $round") { connected(tB) }
            tB.releaseConnection() // what the delivery engine does after a batch
            eventually(message = "A sees disconnect round $round") { !connected(tA) }
        }
        eventually(message = "B relistens after last release") { tB.connectionState.value is ConnectionState.Listening }
    }

    @Test
    fun userDisconnectKeepsAutomaticListeningAlive() = runBlocking {
        tB.startAutoServerListening()
        eventually { tB.connectionState.value is ConnectionState.Listening }
        assertTrue(tA.connect(b.address, "Bravo"))
        eventually { connected(tB) }
        tB.disconnect()
        eventually(message = "B listening again after disconnect()") { tB.connectionState.value is ConnectionState.Listening }
    }

    // ---------------------------------------------------------------- permission & radio state

    /** Start Server without permission is reported explicitly (no silent no-op); after the grant the user's request is repeated. */
    @Test
    fun missingPermissionIsReportedExplicitlyThenStartSucceedsAfterGrant() {
        b.permission = false
        assertEquals(com.sethu.emergency.comm.ServerStartResult.PERMISSION_MISSING, tB.startServer())
        assertEquals(
            com.sethu.emergency.comm.ServerState.Failed(BluetoothRfcommTransport.MSG_PERMISSION_REQUIRED),
            tB.serverState.value
        )
        assertEquals(
            BluetoothRfcommTransport.MSG_PERMISSION_REQUIRED,
            (tB.connectionState.value as ConnectionState.Failed).reason
        )
        assertEquals("no listen attempt without permission", 0, b.listenCalls.get())

        b.permission = true
        tB.onPermissionsChanged()
        assertEquals(com.sethu.emergency.comm.ServerStartResult.STARTED, tB.startServer())
        eventually(message = "listening after grant") { tB.connectionState.value is ConnectionState.Listening }
    }

    @Test
    fun connectWithoutPermissionFailsHonestlyAndLeavesNoStuckConnecting() = runBlocking {
        a.permission = false
        assertFalse(tA.connect(b.address, "Bravo"))
        val s = tA.connectionState.value
        assertTrue(s is ConnectionState.Failed)
        assertEquals(0, a.connectAttempts.get())
    }

    @Test
    fun bluetoothOffClosesSocketsAndOnRestoresListening() = runBlocking {
        tB.startAutoServerListening()
        eventually { tB.connectionState.value is ConnectionState.Listening }
        assertTrue(tA.connect(b.address, "Bravo"))
        eventually { connected(tB) }

        b.radioOff()
        tB.handleBluetoothDisabled()
        assertEquals(BluetoothRfcommTransport.MSG_BLUETOOTH_DISABLED, (tB.connectionState.value as ConnectionState.Failed).reason)
        eventually(message = "A notices the dropped link") { !connected(tA) }
        assertNull(tB.remotePeerRole.value)

        b.radioOn()
        tB.handleBluetoothEnabled()
        eventually(message = "listening after BT on") { tB.connectionState.value is ConnectionState.Listening }
        assertTrue("reconnect works after toggle", tA.connect(b.address, "Bravo"))
    }

    // ---------------------------------------------------------------- outbound bounds & arbitration

    @Test
    fun connectToUnreachablePeerFailsWithinBoundAndRestoresListening() = runBlocking {
        tA.startAutoServerListening()
        val t0 = System.currentTimeMillis()
        assertFalse("B has no listener -> refused", tA.connect(b.address, "Bravo"))
        assertTrue(System.currentTimeMillis() - t0 < 2000)
        assertEquals("one attempt, no retry storm", 1, a.connectAttempts.get())
        eventually(message = "A listening again after failed connect") { tA.connectionState.value is ConnectionState.Listening }
    }

    @Test
    fun hangingConnectIsAbortedByWatchdog() = runBlocking {
        a.connectHangs = true
        val t0 = System.currentTimeMillis()
        val ok = tA.connect(b.address, "Bravo")
        val elapsed = System.currentTimeMillis() - t0
        assertFalse(ok)
        assertTrue("watchdog must abort within ~connectTimeout (was ${elapsed}ms)", elapsed in 500..2500)
        assertTrue(tA.connectionState.value is ConnectionState.Failed)
    }

    @Test
    fun cancellingCallerAbortsConnectAndLeaksNothing() = runBlocking {
        a.connectHangs = true
        val deferred = async(Dispatchers.Default) { tA.connect(b.address, "Bravo") }
        eventually { tA.connectionState.value is ConnectionState.Connecting }
        deferred.cancel()
        eventually(message = "state leaves Connecting after cancel") { tA.connectionState.value !is ConnectionState.Connecting }
    }

    @Test
    fun simultaneousOpenEndsWithExactlyOneConsistentSessionOrCleanRetry() = runBlocking {
        tA.startAutoServerListening(); tB.startAutoServerListening()
        eventually { tA.connectionState.value is ConnectionState.Listening && tB.connectionState.value is ConnectionState.Listening }
        val d1 = async(Dispatchers.Default) { tA.connect(b.address, "Bravo") }
        val d2 = async(Dispatchers.Default) { tB.connect(a.address, "Alpha") }
        d1.await(); d2.await()
        // Whatever the arbitration outcome, neither side may be stuck Connecting, and both must end up usable:
        eventually(message = "no side stuck in Connecting") {
            tA.connectionState.value !is ConnectionState.Connecting && tB.connectionState.value !is ConnectionState.Connecting
        }
        // A fresh attempt from one side must always work afterwards.
        if (!(connected(tA) && connected(tB))) {
            eventually { tB.connectionState.value is ConnectionState.Listening }
            assertTrue(tA.connect(b.address, "Bravo"))
            eventually { connected(tB) }
        }
    }

    // ---------------------------------------------------------------- stale state & ownership

    @Test
    fun staleReaderCannotClearNewerConnection() = runBlocking {
        val c = net.newAdapter("CC:CC:CC:CC:CC:03", "Charlie")
        val scopeC = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tC = BluetoothRfcommTransport(null, scopeC, c, connectTimeoutMs = 600, listenRetryDelayMs = 30)
        tC.localRoleName = "SOS_SENDER"
        try {
            tB.startAutoServerListening()
            eventually { tB.connectionState.value is ConnectionState.Listening }
            assertTrue(tA.connect(b.address, "Bravo"))
            eventually { connected(tB) }
            // B drops A and immediately connects out to C (listening): A's old reader finishes late.
            tC.startAutoServerListening()
            eventually { tC.connectionState.value is ConnectionState.Listening }
            assertTrue(tB.connect(c.address, "Charlie"))
            eventually { connected(tC) }
            Thread.sleep(200)
            val st = tB.connectionState.value
            assertTrue("B must still be connected to C after A's reader ended", st is ConnectionState.Connected)
            assertEquals(c.address, (st as ConnectionState.Connected).remoteDeviceAddress)
        } finally {
            tC.close(); scopeC.cancel()
        }
    }

    @Test
    fun peerRoleIsResetPerConnection() = runBlocking {
        tB.startAutoServerListening()
        eventually { tB.connectionState.value is ConnectionState.Listening }
        assertTrue(tA.connect(b.address, "Bravo"))
        assertEquals("RELAY_DEVICE", tA.awaitPeerRole(1000))
        tA.releaseConnection()
        assertNull("role of the previous connection must not survive", tA.remotePeerRole.value)
        eventually { tB.connectionState.value is ConnectionState.Listening }
        tB.localRoleName = "COORDINATOR"
        assertTrue(tA.connect(b.address, "Bravo"))
        assertEquals("COORDINATOR", tA.awaitPeerRole(1000))
    }

    @Test
    fun unknownRoleStringIsIgnoredAndNeverBecomesPeerRole() = runBlocking {
        tB.localRoleName = "ADMIN_SUPERUSER"
        tB.startAutoServerListening()
        eventually { tB.connectionState.value is ConnectionState.Listening }
        assertTrue(tA.connect(b.address, "Bravo"))
        assertNull(tA.awaitPeerRole(500))
        assertNull(tA.remotePeerRole.value)
    }

    // ---------------------------------------------------------------- framing & malformed input

    private fun connectPair(): Unit = runBlocking {
        tB.startAutoServerListening()
        eventually { tB.connectionState.value is ConnectionState.Listening }
        assertTrue(tA.connect(b.address, "Bravo"))
        assertNotNull(tA.awaitPeerRole(1000)); assertNotNull(tB.awaitPeerRole(1000))
    }

    @Test
    fun partialReadsStillDeliverEveryFrameIntact() {
        net.readChunk = 5
        connectPair()
        val got = java.util.concurrent.CopyOnWriteArrayList<SosReport>()
        tB.onSosReportReceived = { _, r, _ -> got.add(r) }
        val reports = (1..5).map { SosReport(reportId = "R$it", category = "Fire", peopleCount = "1", notes = "ünïcödé ✓ note $it") }
        reports.forEach { assertTrue(tA.sendSosReport(it)) }
        eventually(message = "5 reports") { got.size == 5 }
        assertEquals(reports.map { it.reportId }, got.map { it.reportId })
        assertEquals("ünïcödé ✓ note 3", got[2].notes)
    }

    @Test
    fun malformedOversizedAndGarbageFramesNeverKillTheLinkOrCreateReports() {
        connectPair()
        val got = java.util.concurrent.CopyOnWriteArrayList<SosReport>()
        tB.onSosReportReceived = { _, r, _ -> got.add(r) }
        // Raw writes from A's side of the fake socket (bypassing the transport's well-formed senders)
        val ch = (tA.javaClass.getDeclaredField("session").also { it.isAccessible = true }.get(tA))!!
        val chan = ch.javaClass.getDeclaredField("channel").also { it.isAccessible = true }.get(ch) as com.sethu.emergency.comm.bluetooth.RfcommChannel
        fun raw(s: String) { chan.output.write((s + "\n").toByteArray()); chan.output.flush() }
        raw("this is not json")
        raw("{\"messageType\":\"SOS_REPORT\",\"payload\":\"{not json}\"}")
        raw("{\"messageType\":\"SOS_REPORT\",\"payload\":\"{\\\"category\\\":\\\"Fire\\\"}\"}") // no reportId
        raw("{\"messageType\":\"FUTURE_TYPE\",\"messageId\":\"x1\",\"payload\":\"?\"}")
        raw("x".repeat(200_000)) // oversized frame
        assertTrue(tA.sendSosReport(SosReport(reportId = "GOOD", category = "Flood", peopleCount = "1")))
        eventually(message = "valid report after garbage") { got.any { it.reportId == "GOOD" } }
        assertEquals("only the valid report may be ingested", listOf("GOOD"), got.map { it.reportId })
        assertTrue(connected(tB))
    }

    @Test
    fun blankOrOversizedAckIdsAreRejected() {
        connectPair()
        val ch = (tB.javaClass.getDeclaredField("session").also { it.isAccessible = true }.get(tB))!!
        val chan = ch.javaClass.getDeclaredField("channel").also { it.isAccessible = true }.get(ch) as com.sethu.emergency.comm.bluetooth.RfcommChannel
        chan.output.write((SethuMessage(senderDeviceName = "x", messageType = SethuMessage.TYPE_SOS_ACK, payload = "  ").toJsonString() + "\n").toByteArray())
        chan.output.write((SethuMessage(senderDeviceName = "x", messageType = SethuMessage.TYPE_SOS_ACK, payload = "y".repeat(500)).toJsonString() + "\n").toByteArray())
        Thread.sleep(150)
        assertTrue(tA.acknowledgedReportIds.value.isEmpty())
    }

    // ---------------------------------------------------------------- failures during I/O

    @Test
    fun peerDisconnectDuringReadResetsStateAndAllowsReconnect() = runBlocking {
        connectPair()
        tB.releaseConnection()
        eventually(message = "A notices") { !connected(tA) }
        assertNull(tA.remotePeerRole.value)
        eventually { tB.connectionState.value is ConnectionState.Listening }
        assertTrue(tA.connect(b.address, "Bravo"))
    }

    @Test
    fun writeFailureClosesSessionAndReportsFalse() {
        connectPair()
        net.interceptor = { _, _, m -> if (m?.messageType == SethuMessage.TYPE_SOS_REPORT) FakeNetwork.Action.SEVER_BEFORE else FakeNetwork.Action.DELIVER }
        assertFalse(tA.sendSosReport(SosReport(reportId = "W1", category = "Fire", peopleCount = "1")))
        eventually(message = "A not connected after failed write") { !connected(tA) }
        eventually(message = "B not connected after peer loss") { !connected(tB) }
        assertFalse("a failed write must never be reported as success", tA.sendSosReport(SosReport(reportId = "W2", category = "x", peopleCount = "1")))
    }

    @Test
    fun duplicateCleanupCallsAreHarmless() {
        connectPair()
        repeat(4) { tA.releaseConnection(); tA.disconnect() }
        tA.close(); tA.close()
        eventually { !connected(tB) }
    }

    @Test
    fun awaitAckReturnsFalseWhenLinkDropsBeforeAck() = runBlocking {
        connectPair()
        // B never acks; drop the link while A waits.
        val waiter = async(Dispatchers.Default) {
            tA.sendSosReportAwaitingAck(SosReport(reportId = "L1", category = "Fire", peopleCount = "1"), SethuMessage.TYPE_SOS_ACK, 5000)
        }
        Thread.sleep(100)
        tB.releaseConnection()
        val t0 = System.currentTimeMillis()
        assertFalse(waiter.await())
        assertTrue("must fail fast on disconnect, not wait the full timeout", System.currentTimeMillis() - t0 < 2000)
    }
}
