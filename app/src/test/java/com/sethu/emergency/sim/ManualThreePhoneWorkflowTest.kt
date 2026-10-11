package com.sethu.emergency.sim

import com.sethu.emergency.comm.ConnectionState
import com.sethu.emergency.comm.ServerState
import com.sethu.emergency.comm.coordinator.CoordinatorReportStore
import com.sethu.emergency.comm.forwarding.EngineStatus
import com.sethu.emergency.comm.forwarding.ForwardingConfig
import com.sethu.emergency.comm.model.SethuMessage
import com.sethu.emergency.comm.relay.DeliveryState
import com.sethu.emergency.comm.relay.RelayReportStore
import com.sethu.emergency.comm.sender.SenderDeliveryState
import com.sethu.emergency.comm.sender.SenderReportStore
import com.sethu.emergency.ui.screens.DeviceRole
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * SIMULATED end-to-end tests of the exact demonstration order (real transport, controller and durable stores;
 * fake in-memory RFCOMM sockets). They prove the application logic only - NOT Android Bluetooth pairing/SDP/radio
 * behaviour on the real iQOO 15 / Vivo Y15 / Motorola Edge 30 Pro. Those need the physical-device run.
 *
 *  Stage 1  manual iQOO <-> Vivo connection           Stage 4  manual Vivo <-> Motorola connection
 *  Stage 2  SOS sent, durably saved on Vivo, ACKed     Stage 5  automatic forward, Moto persists + ACKs,
 *  Stage 3  iQOO Bluetooth off, link ends, SOS kept             Vivo marks delivered
 */
class ManualThreePhoneWorkflowTest : ManualSimBase() {

    // ============================================================================================================
    // 27. The five stages, in order, with the user's manual actions between them
    // ============================================================================================================

    @Test
    fun allFiveStagesInTheRequiredOrder() {
        val (iqoo, vivo, moto) = threePhones()
        val id = "SOS-FIVE-STAGES"

        // ordering evidence recorded on the wire:
        //  - Vivo's SOS_ACK to iQOO may only leave AFTER the report is on Vivo's disk
        //  - Moto's COORDINATOR_ACK to Vivo may only leave AFTER the report is on Moto's disk
        val violations = CopyOnWriteArrayList<String>()
        rules.add { f ->
            when {
                f.type == SethuMessage.TYPE_SOS_ACK && f.from == VIVO && f.payload == id ->
                    if (diskRelay(vivo).getAllReports().none { it.report.reportId == id }) violations.add("relay ACK before relay disk write")
                f.type == SethuMessage.TYPE_COORDINATOR_ACK && f.from == MOTO && f.payload == id ->
                    if (diskCoord(moto).getAllReports().none { it.reportId == id }) violations.add("coordinator ACK before coordinator disk write")
            }
            null
        }

        // ---------------- app is open on all phones: NOTHING listens, NOTHING dials ----------------
        staysTrue(400, "no listener and no outbound connect before the user acts") {
            nodes.all { it.adapter.listenCalls.get() == 0 && it.adapter.connectAttempts.get() == 0 }
        }

        // ---------------- Stage 1: manual iQOO <-> Vivo ----------------
        assertTrue(vivo.startServer().name, vivo.transport.serverState.value is ServerState.Starting ||
            vivo.transport.serverState.value is ServerState.Listening)
        waitFor("Vivo Listening") { vivo.transport.serverState.value is ServerState.Listening }
        assertEquals("iQOO is not listening", 0, iqoo.adapter.listenCalls.get())
        iqoo.connectTo(vivo)
        waitFor("Vivo sees iQOO") { connected(vivo, IQOO) && vivo.transport.remotePeerRole.value == "SOS_SENDER" }
        assertEquals("RELAY_DEVICE", iqoo.transport.remotePeerRole.value)
        assertEquals("exactly one dial: iQOO -> Vivo", 1, iqoo.adapter.connectAttempts.get())
        assertEquals("nobody dialled Motorola", 0, moto.adapter.listenCalls.get() + vivo.adapter.connectAttempts.get())

        // ---------------- Stage 2: SOS iQOO -> Vivo, durable, then ACK ----------------
        iqoo.senderStore.saveReport(iqoo.newReport(id))
        waitFor("iQOO sees Relay ACK") { iqoo.senderStore.getReport(id)?.deliveryState == SenderDeliveryState.ACKNOWLEDGED_BY_RELAY }
        val onVivo = vivo.relayStore.getAllReports().single()
        assertEquals(id, onVivo.report.reportId)
        assertEquals("received and pending onward delivery, NOT delivered", DeliveryState.PENDING_FORWARD, onVivo.deliveryState)
        assertEquals(IQOO, onVivo.sourceDeviceAddress)
        assertTrue(vivo.relayStore.isDurablyStored(id))
        assertEquals(DeliveryState.PENDING_FORWARD, diskRelay(vivo).getAllReports().single().deliveryState)
        assertEquals("exactly one SOS frame iQOO->Vivo", 1, framesOf(SethuMessage.TYPE_SOS_REPORT, IQOO, VIVO).size)
        assertEquals(0, framesOf(SethuMessage.TYPE_SOS_REPORT, VIVO).size)
        assertEquals("Vivo status shows it is waiting for a Coordinator connection",
            EngineStatus.Kind.WAITING_RETRY, vivo.controller.status.value.kind)

        // ---------------- Stage 3: iQOO Bluetooth OFF ----------------
        iqoo.adapter.radioOff()
        waitFor("both phones show disconnected") {
            !connected(vivo, IQOO) && iqoo.transport.connectionState.value !is ConnectionState.Connected
        }
        assertEquals("report kept after the link vanished", DeliveryState.PENDING_FORWARD, vivo.relayStore.getAllReports().single().deliveryState)
        assertEquals(DeliveryState.PENDING_FORWARD, diskRelay(vivo).getAllReports().single().deliveryState)
        // Vivo's listener (which the user started) is back, but Vivo never dials iQOO or Motorola by itself
        waitFor("Vivo listens again") { vivo.transport.serverState.value is ServerState.Listening }
        staysTrue(700, "Vivo does not redial anybody; nothing is forwarded; nothing is marked delivered") {
            vivo.adapter.connectAttempts.get() == 0 &&
                vivo.controller.transmissionsStarted.get() == 0 &&
                vivo.relayStore.getAllReports().single().deliveryState == DeliveryState.PENDING_FORWARD &&
                !connected(vivo, IQOO)
        }

        // ---------------- Stage 4: manual Vivo <-> Motorola ----------------
        moto.startServer()
        waitFor("Moto Listening") { moto.transport.serverState.value is ServerState.Listening }
        staysTrue(500, "Moto listening does not make Vivo connect or forward on its own") {
            vivo.adapter.connectAttempts.get() == 0 && framesOf(SethuMessage.TYPE_SOS_REPORT, VIVO, MOTO).isEmpty()
        }
        assertEquals("no SOS on Moto yet", 0, moto.coordinatorStore.getAllReports().size)

        vivo.connectTo(moto) // the user's tap

        // ---------------- Stage 5: automatic forwarding, no Forward/Retry press ----------------
        waitFor("Moto durably stored the SOS") { moto.coordinatorStore.isDurablyStored(id) }
        waitFor("Vivo marked delivered after Moto's ACK") {
            vivo.relayStore.getAllReports().single().deliveryState == DeliveryState.DELIVERED_TO_COORDINATOR
        }
        assertEquals("iQOO was never needed", 1, iqoo.adapter.connectAttempts.get())
        assertEquals(1, vivo.adapter.connectAttempts.get())
        assertEquals(DeliveryState.DELIVERED_TO_COORDINATOR, diskRelay(vivo).getAllReports().single().deliveryState)
        assertEquals(1, diskCoord(moto).getAllReports().size)
        assertTrue("durability ordering violations: $violations", violations.isEmpty())

        // wire order: report -> (Moto persists) -> COORDINATOR_ACK ; Vivo's DELIVERED can only follow the ACK frame
        val sosFrames = framesOf(SethuMessage.TYPE_SOS_REPORT, VIVO, MOTO)
        val ackFrames = framesOf(SethuMessage.TYPE_COORDINATOR_ACK, MOTO, VIVO)
        assertEquals(1, sosFrames.size)
        assertEquals(1, ackFrames.size)
        assertTrue(sosFrames[0].seq < ackFrames[0].seq)
        assertEquals(id, ackFrames[0].payload)

        // Moto dashboard data: same ID, visible as the latest received
        val latest = com.sethu.emergency.comm.coordinator.CoordinatorDashboard.latestReceived(moto.coordinatorStore.itemsFlow.value)
        assertNotNull(latest)
        assertEquals(id, latest!!.report.reportId)

        // the SOS is not forwarded again, and nothing loops
        val settled = log.size
        staysTrue(900, "no repeated forwarding or ACK loop after confirmation") { log.size == settled }
        assertEquals(1, vivo.controller.transmissionsStarted.get())
        assertEquals("a single delivery worker", 1, vivo.controller.maxConcurrentPasses)

        // restart (process death) keeps every record
        moto.restart()
        vivo.restart()
        assertEquals(1, moto.coordinatorStore.getAllReports().size)
        assertEquals(DeliveryState.DELIVERED_TO_COORDINATOR, vivo.relayStore.getAllReports().single().deliveryState)
        staysTrue(400, "restart does not resend a delivered report") { framesOf(SethuMessage.TYPE_SOS_REPORT, VIVO, MOTO).size == 1 }
    }

    // ============================================================================================================
    // Deliberate disconnect / policy details
    // ============================================================================================================

    private fun stageOneAndTwo(): Triple<SimNode, SimNode, SimNode> {
        val phones = threePhones()
        val (iqoo, vivo, _) = phones
        vivo.startServer()
        waitFor("Vivo Listening") { vivo.transport.serverState.value is ServerState.Listening }
        iqoo.connectTo(vivo)
        iqoo.senderStore.saveReport(iqoo.newReport("SOS-X"))
        waitFor("relay ACK") { iqoo.senderStore.getReport("SOS-X")?.deliveryState == SenderDeliveryState.ACKNOWLEDGED_BY_RELAY }
        return phones
    }

    @Test
    fun deliberateDisconnectStopsTheLinkButKeepsTheReportAndNeverRedials() {
        val (iqoo, vivo, _) = stageOneAndTwo()

        vivo.transport.disconnect() // user presses Disconnect on Vivo

        waitFor("link gone on both") { !connected(vivo, IQOO) && iqoo.transport.connectionState.value !is ConnectionState.Connected }
        assertEquals(DeliveryState.PENDING_FORWARD, vivo.relayStore.getAllReports().single().deliveryState)
        assertTrue(vivo.relayStore.isDurablyStored("SOS-X"))
        staysTrue(900, "neither phone redials after a deliberate disconnect") {
            iqoo.adapter.connectAttempts.get() == 1 && vivo.adapter.connectAttempts.get() == 0
        }
        // and the app on Vivo is still usable: it can start the manual connection later
        assertFalse(vivo.transport.connectionState.value is ConnectionState.Failed)
    }

    @Test
    fun forwardingFailureLeavesTheReportPendingAndAReconnectResumesIt() {
        val cfg = fastConfig().copy(ackTimeoutMs = 300)
        val (iqoo, vivo, moto) = threePhones(cfg)
        val id = "SOS-RETRY"
        vivo.startServer(); waitFor("listen") { vivo.transport.serverState.value is ServerState.Listening }
        iqoo.connectTo(vivo)
        iqoo.senderStore.saveReport(iqoo.newReport(id))
        waitFor("relay ACK") { iqoo.senderStore.getReport(id)?.deliveryState == SenderDeliveryState.ACKNOWLEDGED_BY_RELAY }
        iqoo.adapter.radioOff()
        waitFor("iQOO gone") { !connected(vivo, IQOO) }

        // The Coordinator's ACK is lost on the air.
        val dropAcks = AtomicInteger()
        rules.add { f -> if (f.type == SethuMessage.TYPE_COORDINATOR_ACK && f.from == MOTO) { dropAcks.incrementAndGet(); FakeNetwork.Action.DROP } else null }

        moto.startServer(); waitFor("moto listen") { moto.transport.serverState.value is ServerState.Listening }
        vivo.connectTo(moto)
        waitFor("Moto stored it") { moto.coordinatorStore.isDurablyStored(id) }
        waitFor("ack was lost at least once") { dropAcks.get() >= 1 }
        waitFor("attempts used up") { vivo.controller.transmissionsStarted.get() >= 3 }
        Thread.sleep(500)
        val state = vivo.relayStore.getAllReports().single().deliveryState
        assertTrue("never delivered without an ACK, was $state",
            state == DeliveryState.FORWARD_FAILED || state == DeliveryState.FORWARDED_TO_COORDINATOR)
        assertEquals("bounded retries per link (no storm)", 3, vivo.controller.transmissionsStarted.get())

        // Radio recovers; the user reconnects; the queue resumes by itself.
        rules.clear()
        vivo.transport.disconnect()
        waitFor("link down") { !connected(vivo, MOTO) }
        vivo.connectTo(moto)
        waitFor("delivered after reconnect") { vivo.relayStore.getAllReports().single().deliveryState == DeliveryState.DELIVERED_TO_COORDINATOR }
        assertEquals("Moto de-duplicated the retransmissions", 1, moto.coordinatorStore.getAllReports().size)
    }

    @Test
    fun repeatedConnectDisconnectNeverCreatesParallelWorkersOrUnboundedResends() {
        val cfg = fastConfig().copy(ackTimeoutMs = 150)
        val (iqoo, vivo, moto) = threePhones(cfg)
        val id = "SOS-FLAP"
        vivo.startServer(); waitFor("listen") { vivo.transport.serverState.value is ServerState.Listening }
        iqoo.connectTo(vivo)
        iqoo.senderStore.saveReport(iqoo.newReport(id))
        waitFor("relay ACK") { iqoo.senderStore.getReport(id)?.deliveryState == SenderDeliveryState.ACKNOWLEDGED_BY_RELAY }
        iqoo.adapter.radioOff(); waitFor("iQOO gone") { !connected(vivo, IQOO) }

        rules.add { f -> if (f.type == SethuMessage.TYPE_COORDINATOR_ACK) FakeNetwork.Action.DROP else null }
        moto.startServer(); waitFor("moto listen") { moto.transport.serverState.value is ServerState.Listening }
        val rounds = 4
        repeat(rounds) {
            vivo.connectTo(moto)
            waitFor("some forwarding attempt this round") { vivo.controller.transmissionsStarted.get() >= it + 1 }
            vivo.transport.disconnect()
            waitFor("link down") { !connected(vivo, MOTO) }
        }
        Thread.sleep(300)
        assertEquals(1, vivo.controller.maxConcurrentPasses)
        assertTrue("at most 3 sends per connection", vivo.controller.transmissionsStarted.get() <= 3 * rounds)
        assertEquals(1, moto.coordinatorStore.getAllReports().size)
        assertTrue(vivo.relayStore.getAllReports().single().deliveryState != DeliveryState.DELIVERED_TO_COORDINATOR)
    }

    @Test
    fun duplicatedFramesNeverDuplicateReports() {
        val (iqoo, vivo, moto) = threePhones()
        val id = "SOS-DUP"
        rules.add { f -> if (f.type == SethuMessage.TYPE_SOS_REPORT) FakeNetwork.Action.DUPLICATE else null }
        vivo.startServer(); waitFor("listen") { vivo.transport.serverState.value is ServerState.Listening }
        iqoo.connectTo(vivo)
        iqoo.senderStore.saveReport(iqoo.newReport(id))
        waitFor("relay ACK") { iqoo.senderStore.getReport(id)?.deliveryState == SenderDeliveryState.ACKNOWLEDGED_BY_RELAY }
        iqoo.adapter.radioOff(); waitFor("gone") { !connected(vivo, IQOO) }
        moto.startServer(); waitFor("moto listen") { moto.transport.serverState.value is ServerState.Listening }
        vivo.connectTo(moto)
        waitFor("delivered") { vivo.relayStore.getAllReports().singleOrNull()?.deliveryState == DeliveryState.DELIVERED_TO_COORDINATOR }
        assertEquals(1, vivo.relayStore.getAllReports().size)
        assertEquals(1, moto.coordinatorStore.getAllReports().size)
        assertEquals(1, iqoo.senderStore.getAllReports().size)
    }

    @Test
    fun relayRestartedWithPendingReportStillForwardsAfterTheUserConnects() {
        val (iqoo, vivo, moto) = stageOneAndTwo()
        iqoo.adapter.radioOff(); waitFor("gone") { !connected(vivo, IQOO) }
        val listensBefore = vivo.adapter.listenCalls.get()
        val dialsBefore = vivo.adapter.connectAttempts.get()
        vivo.restart() // app killed and reopened: queue is on disk, nothing listens, nothing dials
        assertEquals(DeliveryState.PENDING_FORWARD, vivo.relayStore.getAllReports().single().deliveryState)
        staysTrue(400, "restart does not auto-connect") { vivo.adapter.connectAttempts.get() == dialsBefore && vivo.adapter.listenCalls.get() == listensBefore }
        moto.startServer(); waitFor("moto listen") { moto.transport.serverState.value is ServerState.Listening }
        vivo.connectTo(moto)
        waitFor("delivered") { vivo.relayStore.getAllReports().single().deliveryState == DeliveryState.DELIVERED_TO_COORDINATOR }
        assertEquals(1, moto.coordinatorStore.getAllReports().size)
    }

    @Test
    fun corruptReportToCoordinatorIsRejectedWithoutAckOrRecord() {
        val (iqoo, vivo, moto) = threePhones()
        moto.startServer(); waitFor("moto listen") { moto.transport.serverState.value is ServerState.Listening }
        // Sender talks to the Coordinator directly with a garbage report payload
        iqoo.connectTo(moto)
        val bad = SethuMessage(senderDeviceName = "iQOO 15", messageType = SethuMessage.TYPE_SOS_REPORT, payload = "{not-a-report")
        assertTrue(iqoo.transport.sendMessage(bad.payload, SethuMessage.TYPE_SOS_REPORT))
        staysTrue(400, "no record, no ACK") {
            moto.coordinatorStore.getAllReports().isEmpty() && framesOf(SethuMessage.TYPE_COORDINATOR_ACK, MOTO).isEmpty()
        }
    }

    @Test
    fun senderNeedNotBeOnlineAndRelayNeverSendsBackToItsSource() {
        // Relay connects (by the user) to the Sender that originated the report: it must not forward it back.
        val (iqoo, vivo, _) = stageOneAndTwo()
        val before = framesOf(SethuMessage.TYPE_SOS_REPORT, VIVO).size
        staysTrue(500, "Relay does not route the SOS back to the Sender") {
            framesOf(SethuMessage.TYPE_SOS_REPORT, VIVO, IQOO).isEmpty() && framesOf(SethuMessage.TYPE_SOS_REPORT, VIVO).size == before
        }
        assertEquals(DeliveryState.PENDING_FORWARD, vivo.relayStore.getAllReports().single().deliveryState)
    }
}
