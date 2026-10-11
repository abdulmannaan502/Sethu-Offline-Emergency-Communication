package com.sethu.emergency.comm.forwarding

import com.sethu.emergency.comm.bluetooth.BluetoothRfcommTransport
import com.sethu.emergency.comm.coordinator.CoordinatorReportStore
import com.sethu.emergency.comm.model.SethuMessage
import com.sethu.emergency.comm.model.SosReport
import com.sethu.emergency.comm.relay.RelayReportStore
import com.sethu.emergency.comm.sender.SenderDeliveryState
import com.sethu.emergency.comm.sender.SenderReportStore
import com.sethu.emergency.ui.screens.DeviceRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class StoreAndForwardControllerTest {

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
            category = "Trapped",
            peopleCount = "2",
            childrenInvolved = true,
            elderlyInvolved = false,
            medicalEmergency = false,
            notes = "Emergency on 2nd floor",
            latitude = 12.9716,
            longitude = 77.5946,
            senderDeviceName = senderName
        )
    }

    @Test
    fun testCoordinatorIngestion_DuplicateRetransmissionGeneratesFreshAckWithoutDuplication() = runBlocking {
        controller.setLocalRole(DeviceRole.COORDINATOR)
        val report = createSampleReport()
        val msg = SethuMessage(
            messageId = report.reportId,
            senderDeviceName = "Vivo Y15",
            messageType = SethuMessage.TYPE_SOS_REPORT,
            payload = report.toJsonString()
        )

        // First delivery attempt
        controller.handleIncomingSosReport(msg, report)
        assertEquals(1, coordinatorStore.getAllReports().size)

        // Simulated retransmission (e.g. sender didn't receive initial ACK over the air)
        controller.handleIncomingSosReport(msg, report)
        assertEquals("Duplicate must not create additional entries", 1, coordinatorStore.getAllReports().size)
        assertTrue("Durable store must contain the report", coordinatorStore.getAllReports().any { it.reportId == report.reportId })
    }

    @Test
    fun testRelayIngestion_DuplicateRetransmissionPreservesDurableState() = runBlocking {
        controller.setLocalRole(DeviceRole.RELAY_DEVICE)
        val report = createSampleReport()
        val msg = SethuMessage(
            messageId = report.reportId,
            senderDeviceName = "iQOO 15",
            messageType = SethuMessage.TYPE_SOS_REPORT,
            payload = report.toJsonString()
        )

        controller.handleIncomingSosReport(msg, report)
        assertEquals(1, relayStore.getAllReports().size)

        // Retransmission
        controller.handleIncomingSosReport(msg, report)
        assertEquals("Relay store count must remain 1 on duplicate retransmission", 1, relayStore.getAllReports().size)
    }

    @Test
    fun testControllerStartAndStopLifecycle() {
        assertFalse(controller.isControllerRunning.value)
        controller.start()
        assertTrue(controller.isControllerRunning.value)
        controller.stop()
        assertFalse(controller.isControllerRunning.value)
    }

    @Test
    fun testRoleSwitchingUpdatesStateCleanly() {
        controller.setLocalRole(DeviceRole.SOS_SENDER)
        assertEquals(DeviceRole.SOS_SENDER, controller.localRole.value)

        controller.setLocalRole(DeviceRole.RELAY_DEVICE)
        assertEquals(DeviceRole.RELAY_DEVICE, controller.localRole.value)

        controller.setLocalRole(DeviceRole.COORDINATOR)
        assertEquals(DeviceRole.COORDINATOR, controller.localRole.value)
    }
}
