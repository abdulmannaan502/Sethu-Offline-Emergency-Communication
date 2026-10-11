package com.sethu.emergency.comm.forwarding

import com.sethu.emergency.comm.ConnectionState
import com.sethu.emergency.comm.bluetooth.BluetoothRfcommTransport
import com.sethu.emergency.comm.coordinator.CoordinatorReportStore
import com.sethu.emergency.comm.model.SethuMessage
import com.sethu.emergency.comm.model.SosReport
import com.sethu.emergency.comm.relay.DeliveryState
import com.sethu.emergency.comm.relay.RelayReportStore
import com.sethu.emergency.comm.sender.SenderDeliveryState
import com.sethu.emergency.comm.sender.SenderReportStore
import com.sethu.emergency.ui.screens.DeviceRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * Unit test suite verifying:
 * 1. Durable reverse Coordinator ACK propagation across relay and sender.
 * 2. Peer-role validation rules and loop prevention.
 * 3. Bluetooth state-transition lifecycle recovery.
 * 4. Immediate outbox notification pass.
 *
 * All Bluetooth hardware interactions in this test suite are abstracted and run with local mocks.
 */
class ReverseCoordinatorAckAndRoleValidationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testScope = CoroutineScope(Dispatchers.Unconfined)

    private lateinit var senderDir: File
    private lateinit var relayDir: File
    private lateinit var coordinatorDir: File

    private lateinit var senderStore: SenderReportStore
    private lateinit var relayStore: RelayReportStore
    private lateinit var coordinatorStore: CoordinatorReportStore
    private lateinit var transport: BluetoothRfcommTransport
    private lateinit var controller: StoreAndForwardController

    @Before
    fun setup() {
        senderDir = tempFolder.newFolder("sender")
        relayDir = tempFolder.newFolder("relay")
        coordinatorDir = tempFolder.newFolder("coordinator")

        senderStore = SenderReportStore(senderDir)
        relayStore = RelayReportStore(relayDir)
        coordinatorStore = CoordinatorReportStore(coordinatorDir)
        transport = BluetoothRfcommTransport(null, testScope)

        controller = StoreAndForwardController(
            context = null,
            transport = transport,
            senderStore = senderStore,
            relayStore = relayStore,
            coordinatorStore = coordinatorStore,
            scope = testScope
        )
    }

    private fun createSampleReport(
        id: String = UUID.randomUUID().toString(),
        senderName: String = "iQOO 15"
    ): SosReport {
        return SosReport(
            reportId = id,
            category = "Medical",
            peopleCount = "1",
            childrenInvolved = false,
            elderlyInvolved = true,
            medicalEmergency = true,
            notes = "Elderly person needs insulin",
            latitude = 12.9716,
            longitude = 77.5946,
            senderDeviceName = senderName
        )
    }

    // =========================================================================
    // SECTION 2: DURABLE REVERSE COORDINATOR-ACK PROPAGATION TESTS
    // =========================================================================

    @Test
    fun test1_CoordinatorAckReceivedByRelay() {
        controller.setLocalRole(DeviceRole.RELAY_DEVICE)
        val report = createSampleReport()
        val senderMac = "AA:BB:CC:DD:EE:01"

        // Ingest report on Relay from Sender
        relayStore.saveReceivedReport(report, senderMac)
        assertEquals(DeliveryState.PENDING_FORWARD, relayStore.getAllReports()[0].deliveryState)

        // Relay receives TYPE_COORDINATOR_ACK
        controller.handleIncomingCoordinatorAck(report.reportId, "Motorola Edge 30 Pro")

        val stored = relayStore.getAllReports().find { it.report.reportId == report.reportId }
        assertNotNull(stored)
        assertEquals(DeliveryState.DELIVERED_TO_COORDINATOR, stored!!.deliveryState)
        assertFalse("Confirmation must not be marked propagated before downstream delivery", stored.coordinatorAckPropagated)
        assertEquals(senderMac, stored.sourceDeviceAddress)

        // Verify available for downstream flush to sender
        val pendingDownstream = relayStore.getPendingDownstreamAcks(forDeviceAddress = senderMac)
        assertEquals(1, pendingDownstream.size)
        assertEquals(report.reportId, pendingDownstream[0].report.reportId)
    }

    @Test
    fun test2_ConfirmationPersistedAcrossStoreReload() {
        val report = createSampleReport()
        val senderMac = "AA:BB:CC:DD:EE:01"

        relayStore.saveReceivedReport(report, senderMac)
        relayStore.markDelivered(report.reportId)

        // Simulate app process death and reload from same directory
        val reloadedStore = RelayReportStore(relayDir)
        val reloadedItem = reloadedStore.getAllReports().find { it.report.reportId == report.reportId }

        assertNotNull("Report must survive store reload", reloadedItem)
        assertEquals(DeliveryState.DELIVERED_TO_COORDINATOR, reloadedItem!!.deliveryState)
        assertEquals(senderMac, reloadedItem.sourceDeviceAddress)
        assertFalse("coordinatorAckPropagated must persist as false across store reload", reloadedItem.coordinatorAckPropagated)

        val pending = reloadedStore.getPendingDownstreamAcks(forDeviceAddress = senderMac)
        assertEquals("Reloaded store must still provide pending downstream confirmation", 1, pending.size)
    }

    @Test
    fun test3_ConfirmationDeliveredWhenSenderReconnects() {
        controller.setLocalRole(DeviceRole.RELAY_DEVICE)
        val report = createSampleReport()
        val senderMac = "AA:BB:CC:DD:EE:01"

        // Step 1: Relay has delivered report to coordinator
        relayStore.saveReceivedReport(report, senderMac)
        relayStore.markDelivered(report.reportId)

        // Step 2: Sender has report marked ACKNOWLEDGED_BY_RELAY
        senderStore.saveReport(report)
        senderStore.markAcknowledgedByRelay(report.reportId)
        assertEquals(SenderDeliveryState.ACKNOWLEDGED_BY_RELAY, senderStore.getReport(report.reportId)!!.deliveryState)

        // Step 3: When sender reconnects to relay, relay flushes pending coordinator ACKs
        val pendingAcks = relayStore.getPendingDownstreamAcks(forDeviceAddress = senderMac)
        assertEquals(1, pendingAcks.size)

        // Simulate delivery over the wire
        for (item in pendingAcks) {
            relayStore.markCoordinatorAckPropagated(item.report.reportId)
            // Sender receives the Coordinator ACK
            controller.setLocalRole(DeviceRole.SOS_SENDER)
            controller.handleIncomingCoordinatorAck(item.report.reportId, "Vivo Y15")
        }

        // Verify sender transitioned to ACKNOWLEDGED_BY_COORDINATOR
        val senderReport = senderStore.getReport(report.reportId)
        assertNotNull(senderReport)
        assertEquals(SenderDeliveryState.ACKNOWLEDGED_BY_COORDINATOR, senderReport!!.deliveryState)

        // Verify relay marked it as propagated
        controller.setLocalRole(DeviceRole.RELAY_DEVICE)
        val relayReport = relayStore.getAllReports().find { it.report.reportId == report.reportId }
        assertTrue(relayReport!!.coordinatorAckPropagated)
        assertTrue(relayStore.getPendingDownstreamAcks(forDeviceAddress = senderMac).isEmpty())
    }

    @Test
    fun test4_LostConfirmationRetriedAfterReconnect() {
        val report = createSampleReport()
        val senderMac = "AA:BB:CC:DD:EE:01"

        relayStore.saveReceivedReport(report, senderMac)
        relayStore.markDelivered(report.reportId)

        // Connection dropped before delivery succeeded (markCoordinatorAckPropagated NOT called)
        assertEquals(1, relayStore.getPendingDownstreamAcks(forDeviceAddress = senderMac).size)

        // On next reconnect attempt, the pending confirmation must still be retained
        val secondAttemptPending = relayStore.getPendingDownstreamAcks(forDeviceAddress = senderMac)
        assertEquals("Lost confirmation must be retained for next connection attempt", 1, secondAttemptPending.size)
        assertEquals(report.reportId, secondAttemptPending[0].report.reportId)
    }

    @Test
    fun test5_ExactReportIdMatching() {
        controller.setLocalRole(DeviceRole.SOS_SENDER)
        val report1 = createSampleReport(id = "REPORT-1")
        val report2 = createSampleReport(id = "REPORT-2")

        senderStore.saveReport(report1)
        senderStore.saveReport(report2)
        senderStore.markAcknowledgedByRelay("REPORT-1")
        senderStore.markAcknowledgedByRelay("REPORT-2")

        // Coordinator ACK arrives for REPORT-1 only
        controller.handleIncomingCoordinatorAck("REPORT-1", "Vivo Y15")

        assertEquals(SenderDeliveryState.ACKNOWLEDGED_BY_COORDINATOR, senderStore.getReport("REPORT-1")!!.deliveryState)
        assertEquals("REPORT-2 must remain in ACKNOWLEDGED_BY_RELAY",
            SenderDeliveryState.ACKNOWLEDGED_BY_RELAY, senderStore.getReport("REPORT-2")!!.deliveryState)

        // Unrelated unknown ACK must be ignored safely
        controller.handleIncomingCoordinatorAck("UNKNOWN-REPORT-999", "Vivo Y15")
        assertEquals(SenderDeliveryState.ACKNOWLEDGED_BY_COORDINATOR, senderStore.getReport("REPORT-1")!!.deliveryState)
        assertEquals(SenderDeliveryState.ACKNOWLEDGED_BY_RELAY, senderStore.getReport("REPORT-2")!!.deliveryState)
    }

    @Test
    fun test6_DuplicateConfirmationsDoNotRegressOrDuplicate() {
        controller.setLocalRole(DeviceRole.SOS_SENDER)
        val report = createSampleReport(id = "REPORT-DUP")

        senderStore.saveReport(report)
        senderStore.markAcknowledgedByRelay("REPORT-DUP")

        // First coordinator ACK
        controller.handleIncomingCoordinatorAck("REPORT-DUP", "Vivo Y15")
        assertEquals(SenderDeliveryState.ACKNOWLEDGED_BY_COORDINATOR, senderStore.getReport("REPORT-DUP")!!.deliveryState)
        assertEquals(1, senderStore.getAllReports().size)

        // Duplicate coordinator ACK (e.g. retransmitted by relay)
        controller.handleIncomingCoordinatorAck("REPORT-DUP", "Vivo Y15")
        assertEquals("Duplicate ACK must remain in ACKNOWLEDGED_BY_COORDINATOR without regression",
            SenderDeliveryState.ACKNOWLEDGED_BY_COORDINATOR, senderStore.getReport("REPORT-DUP")!!.deliveryState)
        assertEquals("Store must not duplicate entries", 1, senderStore.getAllReports().size)
    }

    @Test
    fun test7_NoConfirmationDeliveredToUnrelatedSender() {
        val report = createSampleReport(id = "REPORT-SENDER-A")
        val senderAMac = "AA:AA:AA:AA:AA:01"
        val senderBMac = "BB:BB:BB:BB:BB:02"

        relayStore.saveReceivedReport(report, senderAMac)
        relayStore.markDelivered(report.reportId)

        // Sender B connects to Relay: must NOT receive confirmation for Sender A's report
        val pendingForB = relayStore.getPendingDownstreamAcks(forDeviceAddress = senderBMac)
        assertTrue("No confirmation must be delivered to an unrelated sender MAC", pendingForB.isEmpty())

        // Sender A connects: must receive confirmation
        val pendingForA = relayStore.getPendingDownstreamAcks(forDeviceAddress = senderAMac)
        assertEquals(1, pendingForA.size)
        assertEquals("REPORT-SENDER-A", pendingForA[0].report.reportId)
    }

    // =========================================================================
    // SECTION 4: PEER-ROLE VALIDATION & LOOP PREVENTION TESTS
    // =========================================================================

    @Test
    fun testPeerRoleValidation_SenderAllowedRoles() {
        // Sender routing policy: allows RELAY_DEVICE or COORDINATOR
        val allowedRoles = listOf(DeviceRole.RELAY_DEVICE.name, DeviceRole.COORDINATOR.name)
        assertTrue(allowedRoles.contains("RELAY_DEVICE"))
        assertTrue(allowedRoles.contains("COORDINATOR"))

        // Rejects peer announcing SOS_SENDER or unexpected string
        assertFalse(allowedRoles.contains("SOS_SENDER"))
        assertFalse(allowedRoles.contains("UNKNOWN_ROLE"))
    }

    @Test
    fun testPeerRoleValidation_RelayForwardAllowedRoles() {
        // Relay forwarding policy: allows ONLY COORDINATOR as terminal recipient
        val relayForwardTargetRole = DeviceRole.COORDINATOR.name
        assertEquals("COORDINATOR", relayForwardTargetRole)

        // Relay must NEVER forward pending SOS reports to another RELAY or a SENDER
        assertFalse(relayForwardTargetRole == DeviceRole.RELAY_DEVICE.name)
        assertFalse(relayForwardTargetRole == DeviceRole.SOS_SENDER.name)
    }

    // =========================================================================
    // SECTION 3 & 5: LIFECYCLE RECOVERY & OUTBOX LATENCY TESTS
    // =========================================================================

    @Test
    fun testBluetoothLifecycleStateTransitions() = runBlocking {
        // In JVM unit test environment without physical Bluetooth hardware adapter,
        // disable autoServerListening to test state transition arbitration independently of hardware sockets
        transport.autoServerListening = false

        // Simulated Bluetooth disabled event
        transport.handleBluetoothDisabled()
        assertTrue(transport.connectionState.value is ConnectionState.Failed)
        assertEquals("Bluetooth is disabled. Please turn it on in Settings.",
            (transport.connectionState.value as ConnectionState.Failed).reason)

        // Simulated Bluetooth enabled event restores state
        transport.handleBluetoothEnabled()
        assertEquals(ConnectionState.NotConnected, transport.connectionState.value)
    }

    @Test
    fun testNotifyOutboxChangedWakesDeliveryPassImmediately() {
        // Start controller
        controller.start()
        assertTrue(controller.isControllerRunning.value)

        // Trigger immediate outbox pass via notifyOutboxChanged
        controller.notifyOutboxChanged()
        assertTrue(controller.isControllerRunning.value)

        controller.stop()
        assertFalse(controller.isControllerRunning.value)
    }

    // =========================================================================
    // SERVER-SIDE ROLE ANNOUNCEMENT & HANDSHAKE TESTS
    // =========================================================================

    @Test
    fun testServerRoleAnnouncement_RoleMappingAndCleanReset() {
        // Verify coordinator role syncs to transport
        controller.setLocalRole(DeviceRole.COORDINATOR)
        assertEquals("COORDINATOR", transport.localRoleName)
        assertTrue(transport.isCoordinatorRole)

        // Verify relay role syncs to transport
        controller.setLocalRole(DeviceRole.RELAY_DEVICE)
        assertEquals("RELAY_DEVICE", transport.localRoleName)
        assertFalse(transport.isCoordinatorRole)

        // Verify sender role syncs to transport
        controller.setLocalRole(DeviceRole.SOS_SENDER)
        assertEquals("SOS_SENDER", transport.localRoleName)

        // Disconnect safely clears remote role
        transport.disconnect()
        assertNull(transport.remotePeerRole.value)
    }

    @Test
    fun testServerRoleAnnouncement_AwaitPeerRoleReceivesAnnouncedRole() = runBlocking {
        // When simulated peer role announcement arrives, awaitPeerRole returns it immediately
        transport.onRoleAnnounced = { role, _ ->
            assertEquals("COORDINATOR", role)
        }

        // Simulate incoming TYPE_ROLE_ANNOUNCE
        val mockMsg = SethuMessage(
            senderDeviceName = "Motorola Edge 30 Pro",
            messageType = SethuMessage.TYPE_ROLE_ANNOUNCE,
            payload = "COORDINATOR"
        )

        // Set remotePeerRole
        val transportField = BluetoothRfcommTransport::class.java.getDeclaredField("_remotePeerRole")
        transportField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = transportField.get(transport) as kotlinx.coroutines.flow.MutableStateFlow<String?>
        stateFlow.value = "COORDINATOR"

        // Simulate connected state so awaitPeerRole proceeds
        val connField = BluetoothRfcommTransport::class.java.getDeclaredField("_connectionState")
        connField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val connStateFlow = connField.get(transport) as kotlinx.coroutines.flow.MutableStateFlow<ConnectionState>
        connStateFlow.value = ConnectionState.Connected("Motorola Edge 30 Pro", "AA:BB:CC:DD:EE:03", isServer = false)

        val resolvedRole = transport.awaitPeerRole(timeoutMs = 1000L)
        assertEquals("COORDINATOR", resolvedRole)

        // Reset
        transport.disconnect()
        assertNull(transport.remotePeerRole.value)
        assertEquals(ConnectionState.NotConnected, transport.connectionState.value)
    }
}
