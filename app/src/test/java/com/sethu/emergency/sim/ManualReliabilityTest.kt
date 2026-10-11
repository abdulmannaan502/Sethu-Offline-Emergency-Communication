package com.sethu.emergency.sim

import com.sethu.emergency.comm.ConnectionState
import com.sethu.emergency.comm.forwarding.EngineStatus
import com.sethu.emergency.comm.model.SethuMessage
import com.sethu.emergency.comm.relay.DeliveryState
import com.sethu.emergency.comm.sender.SenderDeliveryState
import com.sethu.emergency.ui.screens.DeviceRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SIMULATED reliability scenarios (ported from the former auto-dialling SimulatedEndToEndTest / ThreePhone tests).
 * Every connection here is made by the same user actions as in the app (Start Server, tap peer); the engine is
 * never allowed to dial. Not evidence of real Bluetooth behaviour.
 */
class ManualReliabilityTest : ManualSimBase() {

    private val cfg = fastConfig().copy(ackTimeoutMs = 400)

    private fun sender(id: String, p: SimNode) = p.senderStore.getReport(id)?.deliveryState
    private fun relayState(v: SimNode, id: String) = v.relayStore.getAllReports().find { it.report.reportId == id }?.deliveryState

    /** Stage 1+2 for [ids]: Vivo listens, iQOO connects by hand, reports are saved and acknowledged by the Relay. */
    private fun reportsAtRelay(vararg ids: String): Triple<SimNode, SimNode, SimNode> {
        val phones = threePhones(cfg)
        val (iqoo, vivo, _) = phones
        startServerAndWait(vivo)
        connectWhenListening(iqoo, vivo)
        ids.forEach { iqoo.senderStore.saveReport(iqoo.newReport(it)) }
        waitFor("relay ACKs") { ids.all { sender(it, iqoo) == SenderDeliveryState.ACKNOWLEDGED_BY_RELAY } }
        return phones
    }

    /** Stage 4+5: Sender link ended by the user; Coordinator listens; the user taps it on the Relay. */
    private fun connectRelayToCoordinator(iqoo: SimNode, vivo: SimNode, moto: SimNode) {
        if (connected(vivo, IQOO)) linkDown(iqoo, vivo)
        startServerAndWait(moto)
        connectWhenListening(vivo, moto)
    }

    /** The user taps a peer once that peer's own listener is up. */
    private fun connectWhenListening(from: SimNode, to: SimNode) {
        waitFor("${to.name} listens") { to.transport.serverState.value is com.sethu.emergency.comm.ServerState.Listening }
        from.connectTo(to)
    }

    /** Same user tap, but does not wait for the link to survive (for tests whose fault cuts it mid-handshake). */
    private fun tapWhenListening(from: SimNode, to: SimNode) {
        waitFor("${to.name} listens") { to.transport.serverState.value is com.sethu.emergency.comm.ServerState.Listening }
        from.transport.connectToDevice(to.address, to.name)
    }

    private fun endRelayCoordinatorLink(vivo: SimNode) {
        if (vivo.transport.connectionState.value is ConnectionState.Connected) {
            vivo.transport.disconnect()
            waitFor("Vivo link down") { vivo.transport.connectionState.value !is ConnectionState.Connected }
        }
    }

    private fun reconnectSenderToRelay(iqoo: SimNode, vivo: SimNode) {
        endRelayCoordinatorLink(vivo)
        connectWhenListening(iqoo, vivo)
    }

    private fun fullyConfirmed(iqoo: SimNode, vivo: SimNode, id: String) =
        sender(id, iqoo) == SenderDeliveryState.ACKNOWLEDGED_BY_COORDINATOR &&
            vivo.relayStore.getAllReports().find { it.report.reportId == id }?.coordinatorAckPropagated == true

    // ------------------------------------------------------------------------------------------ lost frames

    @Test
    fun lostReportOnFirstAttempt_isRetriedOnTheSameLink_withTheSameId() {
        val (iqoo, vivo, _) = threePhones(cfg)
        once(FakeNetwork.Action.DROP) { it.type == SethuMessage.TYPE_SOS_REPORT && it.from == IQOO }
        startServerAndWait(vivo); connectWhenListening(iqoo, vivo)
        iqoo.senderStore.saveReport(iqoo.newReport("SOS-LOST-REPORT"))
        waitFor("relay ACK after retry") { sender("SOS-LOST-REPORT", iqoo) == SenderDeliveryState.ACKNOWLEDGED_BY_RELAY }
        assertEquals(1, vivo.relayStore.getAllReports().size)
        assertTrue(framesOf(SethuMessage.TYPE_SOS_REPORT, IQOO, VIVO).size >= 2)
        assertEquals("no second iQOO dial: the retry reused the manual link", 1, iqoo.adapter.connectAttempts.get())
    }

    @Test
    fun lostRelayAck_senderRetries_relayDedupesAndReAcks() {
        val (iqoo, vivo, _) = threePhones(cfg)
        once(FakeNetwork.Action.DROP) { it.type == SethuMessage.TYPE_SOS_ACK && it.from == VIVO }
        startServerAndWait(vivo); connectWhenListening(iqoo, vivo)
        iqoo.senderStore.saveReport(iqoo.newReport("SOS-LOST-RACK"))
        waitFor("relay ACK after retry") { sender("SOS-LOST-RACK", iqoo) == SenderDeliveryState.ACKNOWLEDGED_BY_RELAY }
        assertEquals("duplicate delivery must not create a second record", 1, vivo.relayStore.getAllReports().size)
        assertTrue("relay re-acknowledged the retransmission", framesOf(SethuMessage.TYPE_SOS_ACK, VIVO, IQOO).size >= 2)
    }

    @Test
    fun lostCoordinatorAck_relayRetriesOnTheSameLink_coordinatorDedupes() {
        val (iqoo, vivo, moto) = reportsAtRelay("SOS-LOST-CACK")
        once(FakeNetwork.Action.DROP) { it.type == SethuMessage.TYPE_COORDINATOR_ACK && it.from == MOTO }
        connectRelayToCoordinator(iqoo, vivo, moto)
        waitFor("delivered after retry") { relayState(vivo, "SOS-LOST-CACK") == DeliveryState.DELIVERED_TO_COORDINATOR }
        assertEquals(1, moto.coordinatorStore.getAllReports().size)
        assertTrue(framesOf(SethuMessage.TYPE_SOS_REPORT, VIVO, MOTO).size >= 2)
        assertEquals("one manual dial only", 1, vivo.adapter.connectAttempts.get())
    }

    // ------------------------------------------------------------------------------------------ final ACK path (Sender manually reconnects)

    @Test
    fun finalAckReachesTheSenderOnlyWhenItIsManuallyReconnected_andReceiptClosesIt() {
        val (iqoo, vivo, moto) = reportsAtRelay("SOS-FINAL")
        connectRelayToCoordinator(iqoo, vivo, moto)
        waitFor("delivered") { relayState(vivo, "SOS-FINAL") == DeliveryState.DELIVERED_TO_COORDINATOR }
        staysTrue(500, "sender is not contacted by the relay on its own") {
            framesOf(SethuMessage.TYPE_COORDINATOR_ACK, VIVO, IQOO).isEmpty() && vivo.adapter.connectAttempts.get() == 1
        }
        assertEquals(SenderDeliveryState.ACKNOWLEDGED_BY_RELAY, sender("SOS-FINAL", iqoo))
        assertFalse(vivo.relayStore.getAllReports().single().coordinatorAckPropagated)

        reconnectSenderToRelay(iqoo, vivo)
        waitFor("sender confirmed + receipt") { fullyConfirmed(iqoo, vivo, "SOS-FINAL") }
    }

    @Test
    fun finalAckLostOnTheAir_isNotMarkedPropagated_andIsRedeliveredOnTheNextManualLink() {
        val (iqoo, vivo, moto) = reportsAtRelay("SOS-SILENT-LOSS")
        connectRelayToCoordinator(iqoo, vivo, moto)
        waitFor("delivered") { relayState(vivo, "SOS-SILENT-LOSS") == DeliveryState.DELIVERED_TO_COORDINATOR }
        once(FakeNetwork.Action.DROP) { it.type == SethuMessage.TYPE_COORDINATOR_ACK && it.from == VIVO }
        reconnectSenderToRelay(iqoo, vivo)
        waitFor("first final ACK written (and lost)") { framesOf(SethuMessage.TYPE_COORDINATOR_ACK, VIVO, IQOO).isNotEmpty() }
        Thread.sleep(300)
        assertFalse("a successful write is not proof of receipt", vivo.relayStore.getAllReports().single().coordinatorAckPropagated)
        linkDown(iqoo, vivo)
        connectWhenListening(iqoo, vivo)
        waitFor("completes on the next link") { fullyConfirmed(iqoo, vivo, "SOS-SILENT-LOSS") }
        assertTrue(framesOf(SethuMessage.TYPE_COORDINATOR_ACK, VIVO, IQOO).size >= 2)
    }

    @Test
    fun linkCutDuringFinalAck_relayStaysUnconfirmed_untilReceipt() {
        val (iqoo, vivo, moto) = reportsAtRelay("SOS-CUT-FINAL")
        connectRelayToCoordinator(iqoo, vivo, moto)
        waitFor("delivered") { relayState(vivo, "SOS-CUT-FINAL") == DeliveryState.DELIVERED_TO_COORDINATOR }
        val cut = once(FakeNetwork.Action.SEVER_BEFORE) { it.type == SethuMessage.TYPE_COORDINATOR_ACK && it.from == VIVO }
        endRelayCoordinatorLink(vivo)
        tapWhenListening(iqoo, vivo)
        waitFor("cut happened") { cut.get() }
        waitFor("link down") { !connected(vivo, IQOO) }
        assertFalse(vivo.relayStore.getAllReports().single().coordinatorAckPropagated)
        connectWhenListening(iqoo, vivo)
        waitFor("completes after manual reconnect") { fullyConfirmed(iqoo, vivo, "SOS-CUT-FINAL") }
    }

    @Test
    fun lostReceipt_senderStaysCoordinatorAcked_relayResendsOnNextLink_receiptEventuallyArrives() {
        val (iqoo, vivo, moto) = reportsAtRelay("SOS-NO-RECEIPT")
        connectRelayToCoordinator(iqoo, vivo, moto)
        waitFor("delivered") { relayState(vivo, "SOS-NO-RECEIPT") == DeliveryState.DELIVERED_TO_COORDINATOR }
        val cut = once(FakeNetwork.Action.SEVER_BEFORE) { it.type == SethuMessage.TYPE_COORDINATOR_ACK_RECEIPT && it.from == IQOO }
        endRelayCoordinatorLink(vivo)
        tapWhenListening(iqoo, vivo)
        waitFor("receipt cut") { cut.get() }
        waitFor("link down") { !connected(vivo, IQOO) }
        assertEquals(SenderDeliveryState.ACKNOWLEDGED_BY_COORDINATOR, sender("SOS-NO-RECEIPT", iqoo))
        assertFalse("no receipt arrived: relay must keep the final ACK outstanding",
            vivo.relayStore.getAllReports().single().coordinatorAckPropagated)
        connectWhenListening(iqoo, vivo)
        waitFor("receipt arrives") { fullyConfirmed(iqoo, vivo, "SOS-NO-RECEIPT") }
        assertTrue(framesOf(SethuMessage.TYPE_COORDINATOR_ACK, VIVO, IQOO).size >= 2)
    }

    @Test
    fun receiptFromAnUnrelatedSenderNeverClosesTheFinalAck() {
        val (iqoo, vivo, moto) = reportsAtRelay("SOS-FORGED")
        val other = phone("Other Sender", "44:44:44:44:44:44", DeviceRole.SOS_SENDER, cfg)
        other.adapter.bond(vivo.adapter); vivo.adapter.bond(other.adapter)
        connectRelayToCoordinator(iqoo, vivo, moto)
        waitFor("delivered") { relayState(vivo, "SOS-FORGED") == DeliveryState.DELIVERED_TO_COORDINATOR }
        linkDown(vivo, moto)
        waitFor("Vivo listens") { vivo.transport.serverState.value is com.sethu.emergency.comm.ServerState.Listening }

        connectWhenListening(other, vivo)
        waitFor("vivo sees other as sender") { vivo.transport.remotePeerRole.value == "SOS_SENDER" }
        assertTrue(other.transport.sendCoordinatorAckReceipt("SOS-FORGED"))
        assertTrue(other.transport.sendCoordinatorAckReceipt("NOT-A-REPORT"))
        staysTrue(400, "receipt from the wrong address must be ignored") {
            !vivo.relayStore.getAllReports().single().coordinatorAckPropagated &&
                framesOf(SethuMessage.TYPE_COORDINATOR_ACK, VIVO, other.address).isEmpty()
        }
        linkDown(other, vivo)
        connectWhenListening(iqoo, vivo)
        waitFor("legitimate path still completes") { fullyConfirmed(iqoo, vivo, "SOS-FORGED") }
    }

    @Test
    fun duplicatedFramesOfEveryTypeNeverDuplicateRecordsOrRegressState() {
        val (iqoo, vivo, moto) = threePhones(cfg)
        rules.add { f ->
            when (f.type) {
                SethuMessage.TYPE_SOS_REPORT, SethuMessage.TYPE_SOS_ACK, SethuMessage.TYPE_COORDINATOR_ACK,
                SethuMessage.TYPE_COORDINATOR_ACK_RECEIPT -> FakeNetwork.Action.DUPLICATE
                else -> null
            }
        }
        startServerAndWait(vivo); connectWhenListening(iqoo, vivo)
        iqoo.senderStore.saveReport(iqoo.newReport("SOS-DUPES"))
        waitFor("relay ACK") { sender("SOS-DUPES", iqoo) == SenderDeliveryState.ACKNOWLEDGED_BY_RELAY }
        connectRelayToCoordinator(iqoo, vivo, moto)
        waitFor("delivered") { relayState(vivo, "SOS-DUPES") == DeliveryState.DELIVERED_TO_COORDINATOR }
        reconnectSenderToRelay(iqoo, vivo)
        waitFor("complete") { fullyConfirmed(iqoo, vivo, "SOS-DUPES") }
        staysTrue(500, "one record everywhere, terminal states never regress") {
            iqoo.senderStore.getAllReports().size == 1 && vivo.relayStore.getAllReports().size == 1 &&
                moto.coordinatorStore.getAllReports().size == 1 &&
                sender("SOS-DUPES", iqoo) == SenderDeliveryState.ACKNOWLEDGED_BY_COORDINATOR &&
                relayState(vivo, "SOS-DUPES") == DeliveryState.DELIVERED_TO_COORDINATOR
        }
    }

    // ------------------------------------------------------------------------------------------ process restarts at each stage

    @Test
    fun senderKilledBeforeAnyLink_reportReloads_andSendsAfterTheUserConnects() {
        val (iqoo, vivo, _) = threePhones(cfg)
        iqoo.senderStore.saveReport(iqoo.newReport("SOS-REBOOT-SENDER"))
        assertEquals(SenderDeliveryState.PENDING_DELIVERY, sender("SOS-REBOOT-SENDER", iqoo))
        val dials = iqoo.adapter.connectAttempts.get()
        iqoo.restart()
        assertEquals(SenderDeliveryState.PENDING_DELIVERY, sender("SOS-REBOOT-SENDER", iqoo))
        staysTrue(400, "pending SOS does not make the restarted app dial") { iqoo.adapter.connectAttempts.get() == dials }
        startServerAndWait(vivo)
        connectWhenListening(iqoo, vivo)
        waitFor("relay ACK") { sender("SOS-REBOOT-SENDER", iqoo) == SenderDeliveryState.ACKNOWLEDGED_BY_RELAY }
        assertEquals(1, vivo.relayStore.getAllReports().size)
    }

    @Test
    fun senderKilledBetweenRelayAckAndFinalAck_stillCompletesAfterRestartAndManualReconnects() {
        val (iqoo, vivo, moto) = reportsAtRelay("SOS-REBOOT-S2")
        connectRelayToCoordinator(iqoo, vivo, moto)
        waitFor("delivered") { relayState(vivo, "SOS-REBOOT-S2") == DeliveryState.DELIVERED_TO_COORDINATOR }
        iqoo.restart()
        assertEquals(SenderDeliveryState.ACKNOWLEDGED_BY_RELAY, sender("SOS-REBOOT-S2", iqoo))
        reconnectSenderToRelay(iqoo, vivo)
        waitFor("complete") { fullyConfirmed(iqoo, vivo, "SOS-REBOOT-S2") }
    }

    @Test
    fun relayKilledWithPendingFinalAck_resumesAfterRestart() {
        val (iqoo, vivo, moto) = reportsAtRelay("SOS-REBOOT-ACK")
        connectRelayToCoordinator(iqoo, vivo, moto)
        waitFor("delivered") { relayState(vivo, "SOS-REBOOT-ACK") == DeliveryState.DELIVERED_TO_COORDINATOR }
        vivo.restart()
        val reloaded = vivo.relayStore.getAllReports().single()
        assertEquals(DeliveryState.DELIVERED_TO_COORDINATOR, reloaded.deliveryState)
        assertFalse("pending final ACK is still pending after restart", reloaded.coordinatorAckPropagated)
        startServerAndWait(vivo) // the user must start the listener again after a restart
        connectWhenListening(iqoo, vivo)
        waitFor("complete") { fullyConfirmed(iqoo, vivo, "SOS-REBOOT-ACK") }
    }

    @Test
    fun relayKilledBeforeForwarding_forwardsAfterRestartOnlyWhenTheUserConnects() {
        val (iqoo, vivo, moto) = reportsAtRelay("SOS-REBOOT-RELAY")
        linkDown(iqoo, vivo)
        val dials = vivo.adapter.connectAttempts.get()
        vivo.restart()
        assertEquals(DeliveryState.PENDING_FORWARD, relayState(vivo, "SOS-REBOOT-RELAY"))
        startServerAndWait(moto)
        staysTrue(400, "restarted relay does not dial the waiting Coordinator by itself") {
            vivo.adapter.connectAttempts.get() == dials && moto.coordinatorStore.getAllReports().isEmpty()
        }
        connectWhenListening(vivo, moto)
        waitFor("delivered") { relayState(vivo, "SOS-REBOOT-RELAY") == DeliveryState.DELIVERED_TO_COORDINATOR }
    }

    @Test
    fun coordinatorKilled_receivedReportsSurvive_andLaterReportsAreAdded() {
        val (iqoo, vivo, moto) = reportsAtRelay("SOS-COORD-1")
        connectRelayToCoordinator(iqoo, vivo, moto)
        waitFor("delivered") { relayState(vivo, "SOS-COORD-1") == DeliveryState.DELIVERED_TO_COORDINATOR }
        linkDown(vivo, moto)
        moto.restart()
        assertEquals(listOf("SOS-COORD-1"), moto.coordinatorStore.getAllReports().map { it.reportId })

        // a second SOS follows the same manual route
        reconnectSenderToRelay(iqoo, vivo)
        iqoo.senderStore.saveReport(iqoo.newReport("SOS-COORD-2"))
        waitFor("relay ACK") { sender("SOS-COORD-2", iqoo) == SenderDeliveryState.ACKNOWLEDGED_BY_RELAY }
        connectRelayToCoordinator(iqoo, vivo, moto)
        waitFor("second delivered") { relayState(vivo, "SOS-COORD-2") == DeliveryState.DELIVERED_TO_COORDINATOR }
        assertEquals(setOf("SOS-COORD-1", "SOS-COORD-2"), moto.coordinatorStore.getAllReports().map { it.reportId }.toSet())
        assertEquals("one transmission per report: the first was not re-sent", 2, framesOf(SethuMessage.TYPE_SOS_REPORT, VIVO, MOTO).size)
    }

    // ------------------------------------------------------------------------------------------ several pending reports / two senders

    @Test
    fun manyPendingReportsFromTwoSenders_areAllForwardedExactlyOnce() {
        val (iqoo, vivo, moto) = threePhones(cfg)
        val other = phone("Other Sender", "44:44:44:44:44:44", DeviceRole.SOS_SENDER, cfg)
        other.adapter.bond(vivo.adapter); vivo.adapter.bond(other.adapter)
        val idsA = (1..6).map { "A-$it" }
        val idsB = (1..4).map { "B-$it" }
        idsA.forEach { iqoo.senderStore.saveReport(iqoo.newReport(it)) }   // all queued while offline
        idsB.forEach { other.senderStore.saveReport(other.newReport(it)) }
        staysTrue(300, "queued reports do not trigger any connection") { nodes.all { it.adapter.connectAttempts.get() == 0 } }

        startServerAndWait(vivo)
        connectWhenListening(iqoo, vivo)
        waitFor("A reports acknowledged by relay") { idsA.all { sender(it, iqoo) == SenderDeliveryState.ACKNOWLEDGED_BY_RELAY } }
        linkDown(iqoo, vivo)
        waitFor("Vivo listens") { vivo.transport.serverState.value is com.sethu.emergency.comm.ServerState.Listening }
        connectWhenListening(other, vivo)
        waitFor("B reports acknowledged by relay") { idsB.all { sender(it, other) == SenderDeliveryState.ACKNOWLEDGED_BY_RELAY } }
        linkDown(other, vivo)
        assertEquals(10, vivo.relayStore.getAllReports().size)

        startServerAndWait(moto)
        connectWhenListening(vivo, moto)
        waitFor("all 10 delivered") { vivo.relayStore.getAllReports().all { it.deliveryState == DeliveryState.DELIVERED_TO_COORDINATOR } }
        assertEquals(10, moto.coordinatorStore.getAllReports().map { it.reportId }.toSet().size)
        assertEquals("each report sent to the Coordinator exactly once", 10, framesOf(SethuMessage.TYPE_SOS_REPORT, VIVO, MOTO).size)
        assertEquals(1, vivo.controller.maxConcurrentPasses)
    }

    // ------------------------------------------------------------------------------------------ flapping / cut links

    @Test
    fun linkCutMidTransmission_reportStaysPending_andResumesOnTheNextManualLink() {
        val (iqoo, vivo, _) = threePhones(cfg)
        val cut = once(FakeNetwork.Action.SEVER_BEFORE) { it.type == SethuMessage.TYPE_SOS_REPORT && it.from == IQOO }
        startServerAndWait(vivo); connectWhenListening(iqoo, vivo)
        iqoo.senderStore.saveReport(iqoo.newReport("SOS-CUT"))
        waitFor("cut happened") { cut.get() }
        waitFor("link down") { !connected(vivo, IQOO) }
        assertTrue(relayState(vivo, "SOS-CUT") == null)
        assertTrue(sender("SOS-CUT", iqoo) != SenderDeliveryState.ACKNOWLEDGED_BY_RELAY)
        val dials = iqoo.adapter.connectAttempts.get()
        staysTrue(600, "no automatic redial after the cut") { iqoo.adapter.connectAttempts.get() == dials && vivo.adapter.connectAttempts.get() == 0 }
        connectWhenListening(iqoo, vivo)
        waitFor("relay ACK on the new link") { sender("SOS-CUT", iqoo) == SenderDeliveryState.ACKNOWLEDGED_BY_RELAY }
        assertEquals(1, vivo.relayStore.getAllReports().size)
    }

    @Test
    fun senderFlapping_neverResendsPastThePerLinkBudget_andNeverDialsByItself() {
        val (iqoo, vivo, _) = threePhones(cfg)
        rules.add { f -> if (f.type == SethuMessage.TYPE_SOS_ACK) FakeNetwork.Action.DROP else null } // relay ACKs never arrive
        startServerAndWait(vivo)
        iqoo.senderStore.saveReport(iqoo.newReport("SOS-FLAP"))
        val rounds = 3
        repeat(rounds) { round ->
            connectWhenListening(iqoo, vivo)
            waitFor("attempt on link $round") { iqoo.controller.transmissionsStarted.get() >= round + 1 }
            linkDown(iqoo, vivo)
            waitFor("Vivo listens") { vivo.transport.serverState.value is com.sethu.emergency.comm.ServerState.Listening }
        }
        Thread.sleep(300)
        assertEquals(1, iqoo.controller.maxConcurrentPasses)
        assertTrue("max 3 sends per link", iqoo.controller.transmissionsStarted.get() <= 3 * rounds)
        assertEquals("iQOO dialled only when the test (user) told it to", rounds, iqoo.adapter.connectAttempts.get())
        assertEquals(0, vivo.adapter.connectAttempts.get())
        assertEquals(1, vivo.relayStore.getAllReports().size)
    }

    // ------------------------------------------------------------------------------------------ roles / routing safety

    @Test
    fun relayLeftOnSenderRole_isRejectedHonestly_andWorksOnceTheRoleIsFixed() {
        val iqoo = phone("iQOO 15", IQOO, DeviceRole.SOS_SENDER, cfg)
        val vivo = phone("Vivo Y15", VIVO, DeviceRole.SOS_SENDER, cfg) // user never selected "Relay"
        iqoo.adapter.bond(vivo.adapter); vivo.adapter.bond(iqoo.adapter)
        iqoo.senderStore.saveReport(iqoo.newReport("SOS-WRONGROLE"))
        startServerAndWait(vivo)
        iqoo.transport.connectToDevice(VIVO, "Vivo")
        waitFor("wrong-role diagnosis") { iqoo.controller.status.value.kind == EngineStatus.Kind.WRONG_PEER_ROLE }
        waitFor("link dropped") { iqoo.transport.connectionState.value !is ConnectionState.Connected }
        assertEquals("nothing may be sent to a non-relay peer", 0, framesOf(SethuMessage.TYPE_SOS_REPORT, IQOO).size)
        assertEquals(SenderDeliveryState.PENDING_DELIVERY, sender("SOS-WRONGROLE", iqoo))

        vivo.controller.setLocalRole(DeviceRole.RELAY_DEVICE)
        iqoo.transport.connectToDevice(VIVO, "Vivo") // first tap may be refused from the remembered (stale) role...
        Thread.sleep(150)
        iqoo.transport.connectToDevice(VIVO, "Vivo") // ...which is forgotten, so the next tap re-checks over the air
        waitFor("relay ACK after fixing the role") { sender("SOS-WRONGROLE", iqoo) == SenderDeliveryState.ACKNOWLEDGED_BY_RELAY }
        assertEquals(1, iqoo.senderStore.getAllReports().size)
    }

    @Test
    fun senderCanDeliverStraightToTheCoordinatorWhenTheUserConnectsThem() {
        val iqoo = phone("iQOO 15", IQOO, DeviceRole.SOS_SENDER, cfg)
        val moto = phone("Motorola Edge 30 Pro", MOTO, DeviceRole.COORDINATOR, cfg)
        iqoo.adapter.bond(moto.adapter); moto.adapter.bond(iqoo.adapter)
        iqoo.senderStore.saveReport(iqoo.newReport("SOS-DIRECT"))
        startServerAndWait(moto)
        connectWhenListening(iqoo, moto)
        waitFor("coordinator ACK") { sender("SOS-DIRECT", iqoo) == SenderDeliveryState.ACKNOWLEDGED_BY_COORDINATOR }
        assertEquals(1, moto.coordinatorStore.getAllReports().size)
    }

    @Test
    fun relayNeverSendsAReportBackToItsSource_norTheFinalAckToAnUnrelatedSender() {
        val (iqoo, vivo, moto) = reportsAtRelay("SOS-ROUTE")
        val other = phone("Other Sender", "44:44:44:44:44:44", DeviceRole.SOS_SENDER, cfg)
        other.adapter.bond(vivo.adapter); vivo.adapter.bond(other.adapter)
        linkDown(iqoo, vivo)
        waitFor("Vivo listens") { vivo.transport.serverState.value is com.sethu.emergency.comm.ServerState.Listening }
        connectWhenListening(other, vivo)
        staysTrue(500, "relay does not route the SOS to a Sender") {
            framesOf(SethuMessage.TYPE_SOS_REPORT, VIVO).isEmpty()
        }
        linkDown(other, vivo)
        startServerAndWait(moto)
        connectWhenListening(vivo, moto)
        waitFor("delivered") { relayState(vivo, "SOS-ROUTE") == DeliveryState.DELIVERED_TO_COORDINATOR }
        linkDown(vivo, moto)
        waitFor("Vivo listens") { vivo.transport.serverState.value is com.sethu.emergency.comm.ServerState.Listening }
        connectWhenListening(other, vivo)
        staysTrue(500, "a Sender that did not originate the report never receives its final ACK") {
            framesOf(SethuMessage.TYPE_COORDINATOR_ACK, VIVO, other.address).isEmpty()
        }
        assertEquals(0, other.senderStore.getAllReports().size)
    }

    @Test
    fun ackForUnknownReportIdNeverCorruptsSenderState() {
        val (iqoo, _, _) = threePhones(cfg)
        iqoo.senderStore.saveReport(iqoo.newReport("REAL"))
        iqoo.controller.handleIncomingCoordinatorAck("GHOST", "x")
        iqoo.controller.handleIncomingSosAck("GHOST", "x")
        assertEquals(1, iqoo.senderStore.getAllReports().size)
        assertEquals(SenderDeliveryState.PENDING_DELIVERY, sender("REAL", iqoo))
    }

    @Test
    fun relayRecordsSourceAddressFromTheDeliveringSession_evenIfSharedStateMovedOn() {
        val (iqoo, vivo, _) = threePhones(cfg)
        val stateField = vivo.transport.javaClass.getDeclaredField("_connectionState").also { it.isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(vivo.transport) as kotlinx.coroutines.flow.MutableStateFlow<ConnectionState>
        once(FakeNetwork.Action.DELIVER) { f ->
            if (f.type == SethuMessage.TYPE_SOS_REPORT && f.from == IQOO) { state.value = ConnectionState.NotConnected; true } else false
        }
        startServerAndWait(vivo); connectWhenListening(iqoo, vivo)
        iqoo.senderStore.saveReport(iqoo.newReport("SOS-SESSION-SRC"))
        waitFor("relay stored the report") { vivo.relayStore.getAllReports().isNotEmpty() }
        assertEquals(IQOO, vivo.relayStore.getAllReports().single().sourceDeviceAddress)
    }

    // ------------------------------------------------------------------------------------------ permission / radio at engine level

    @Test
    fun missingPermission_isReportedHonestly_queuesSafely_andDeliversAfterTheUserConnects() {
        val (iqoo, vivo, _) = threePhones(cfg)
        startServerAndWait(vivo)
        iqoo.adapter.permission = false
        iqoo.controller.notifyOutboxChanged()
        iqoo.senderStore.saveReport(iqoo.newReport("SOS-NOPERM"))
        waitFor("permission status") { iqoo.controller.status.value.kind == EngineStatus.Kind.PERMISSION_REQUIRED }
        iqoo.transport.connectToDevice(VIVO, "Vivo")
        Thread.sleep(200)
        assertEquals("connect refused without permission", 0, iqoo.adapter.connectAttempts.get())
        assertEquals(SenderDeliveryState.PENDING_DELIVERY, sender("SOS-NOPERM", iqoo))

        iqoo.adapter.permission = true
        iqoo.transport.onPermissionsChanged()
        connectWhenListening(iqoo, vivo)
        waitFor("relay ACK") { sender("SOS-NOPERM", iqoo) == SenderDeliveryState.ACKNOWLEDGED_BY_RELAY }
    }

    @Test
    fun bluetoothOffThenOn_queuesThenDeliversAfterTheUserConnects() {
        val (iqoo, vivo, _) = threePhones(cfg)
        startServerAndWait(vivo)
        iqoo.adapter.radioOff(); iqoo.transport.onBluetoothDisabled()
        iqoo.senderStore.saveReport(iqoo.newReport("SOS-BTOFF"))
        waitFor("BT-off status") { iqoo.controller.status.value.kind == EngineStatus.Kind.BLUETOOTH_OFF }
        assertEquals(SenderDeliveryState.PENDING_DELIVERY, sender("SOS-BTOFF", iqoo))
        iqoo.adapter.radioOn(); iqoo.transport.onBluetoothEnabled()
        iqoo.controller.notifyOutboxChanged()
        staysTrue(400, "turning Bluetooth back on does not dial") { iqoo.adapter.connectAttempts.get() == 0 }
        connectWhenListening(iqoo, vivo)
        waitFor("relay ACK") { sender("SOS-BTOFF", iqoo) == SenderDeliveryState.ACKNOWLEDGED_BY_RELAY }
    }

    @Test
    fun noPairedDevicesAndNoLink_reportsQueuedStatusNotSilence() {
        val iqoo = phone("iQOO 15", IQOO, DeviceRole.SOS_SENDER, cfg)
        iqoo.senderStore.saveReport(iqoo.newReport("SOS-ALONE"))
        waitFor("queued status tells the user what to do") {
            iqoo.controller.status.value.kind == EngineStatus.Kind.WAITING_RETRY &&
                iqoo.controller.status.value.text.contains("Direct Device Link")
        }
        assertEquals(1, iqoo.senderStore.getPendingReports().size)
        assertNotNull(iqoo.senderStore.getReport("SOS-ALONE"))
    }
}
