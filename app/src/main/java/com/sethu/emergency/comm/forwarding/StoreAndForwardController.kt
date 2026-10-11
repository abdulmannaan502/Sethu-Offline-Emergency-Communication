package com.sethu.emergency.comm.forwarding

import android.content.Context
import com.sethu.emergency.comm.ConnectionState
import com.sethu.emergency.comm.RoleCompatibility
import com.sethu.emergency.comm.bluetooth.BluetoothRfcommTransport
import com.sethu.emergency.comm.coordinator.CoordinatorReportStore
import com.sethu.emergency.comm.model.SethuMessage
import com.sethu.emergency.comm.model.SosReport
import com.sethu.emergency.comm.relay.DeliveryState
import com.sethu.emergency.comm.relay.RelayReportStore
import com.sethu.emergency.comm.sender.SenderDeliveryState
import com.sethu.emergency.comm.sender.SenderReportStore
import com.sethu.emergency.ui.screens.DeviceRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** Timing knobs for the delivery engine. Production defaults are used by the app; tests pass short values. */
data class ForwardingConfig(
    /** Safety-net re-evaluation interval; real work is triggered by link/store events, not by this timer. */
    val cyclePollIntervalMs: Long = StoreAndForwardController.CYCLE_POLL_INTERVAL_MS,
    val ackTimeoutMs: Long = StoreAndForwardController.ACK_TIMEOUT_MS,
    /** A report is transmitted at most this many times per physical connection; a new connection resets it. */
    val maxAttemptsPerLink: Int = StoreAndForwardController.MAX_ATTEMPTS_PER_LINK
)

/** Human-readable, truthful engine status for the UI. */
data class EngineStatus(val kind: Kind, val detail: String = "") {
    enum class Kind {
        STOPPED, BLUETOOTH_UNSUPPORTED, BLUETOOTH_OFF, PERMISSION_REQUIRED, NO_PAIRED_DEVICES,
        IDLE, CONNECTING, HANDSHAKING, DELIVERING, WAITING_RETRY, WRONG_PEER_ROLE, LISTENING
    }

    val text: String
        get() = when (kind) {
            Kind.STOPPED -> "Delivery engine stopped"
            Kind.BLUETOOTH_UNSUPPORTED -> "Bluetooth is not supported on this device"
            Kind.BLUETOOTH_OFF -> "Bluetooth is off — turn it on to deliver queued reports"
            Kind.PERMISSION_REQUIRED -> "Nearby-devices permission needed — grant it to enable emergency links"
            Kind.NO_PAIRED_DEVICES -> "No paired devices — pair this phone with the Relay/Coordinator in Android Bluetooth settings"
            Kind.IDLE -> "Nothing queued for delivery"
            Kind.CONNECTING -> "Connecting to $detail…"
            Kind.HANDSHAKING -> "Connected to $detail — verifying role…"
            Kind.DELIVERING -> "Forwarding queued SOS via $detail…"
            Kind.WAITING_RETRY -> if (detail.isNotBlank()) detail else "Queued — open Direct Device Link and connect to the next device"
            Kind.WRONG_PEER_ROLE -> "Connected device $detail is not a valid peer for this role — disconnected"
            Kind.LISTENING -> "Ready (Direct Device Link: Start Server / Connect)"
        }
}

/**
 * Link-driven store-and-forward engine. It NEVER opens or closes connections and never starts a listener: the
 * user connects devices manually (Direct Device Link). As soon as a usable, role-validated link exists, it
 * delivers whatever is pending for that link - with no Forward/Retry action - and keeps the report pending
 * unless the matching, role-validated ACK arrives.
 *
 * - Sender -> Relay or Coordinator: pending SOS is sent; its state only advances on the matching ACK.
 * - Relay -> Coordinator: pending SOS is forwarded; DELIVERED only on a Coordinator ACK for that report ID
 *   received on that same session from a peer that announced the Coordinator role.
 * - Relay never routes a report back to the peer it came from; Coordinator is terminal.
 * - One delivery worker; each report is tried at most [ForwardingConfig.maxAttemptsPerLink] times per physical
 *   connection, so a flapping or silent peer cannot cause a retry storm. A new connection resumes the queue.
 *
 * Final-ACK semantics (Coordinator -> Relay -> Sender):
 *  1. Relay persists DELIVERED_TO_COORDINATOR (coordinatorAckPropagated=false).
 *  2. Whenever the originating Sender is on a link, Relay sends COORDINATOR_ACK (repeatable, idempotent).
 *  3. Sender persists ACKNOWLEDGED_BY_COORDINATOR first, THEN sends COORDINATOR_ACK_RECEIPT.
 *  4. Only a receipt from the matching Sender address makes the Relay set coordinatorAckPropagated=true.
 */
class StoreAndForwardController(
    private val context: Context?,
    private val transport: BluetoothRfcommTransport,
    private val senderStore: SenderReportStore,
    private val relayStore: RelayReportStore,
    private val coordinatorStore: CoordinatorReportStore,
    private val scope: CoroutineScope,
    private val config: ForwardingConfig = ForwardingConfig()
) {

    companion object {
        const val CYCLE_POLL_INTERVAL_MS = 4_000L
        const val ACK_TIMEOUT_MS = 8_000L
        const val MAX_ATTEMPTS_PER_LINK = 3
    }

    private val _localRole = MutableStateFlow(DeviceRole.SOS_SENDER)
    val localRole: StateFlow<DeviceRole> = _localRole.asStateFlow()

    private val _isControllerRunning = MutableStateFlow(false)
    val isControllerRunning: StateFlow<Boolean> = _isControllerRunning.asStateFlow()

    private val _status = MutableStateFlow(EngineStatus(EngineStatus.Kind.STOPPED))
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    // Last role each address announced to us; used only to reject known-incompatible manual connects up front.
    private val knownPeerRoles = ConcurrentHashMap<String, String>()

    // Track the peer address from which a report was received at Relay (reportId -> remoteAddress)
    private val reportSourcePeerMap = ConcurrentHashMap<String, String>()

    // Per-connection transmission attempts: key = "<sessionId>:<reportId>".
    private val attempts = ConcurrentHashMap<String, Int>()

    /** Total SOS transmissions started by the engine (observable for tests/diagnostics). */
    val transmissionsStarted = java.util.concurrent.atomic.AtomicInteger(0)

    /** Number of delivery passes currently executing; must never exceed 1. */
    private val activePasses = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile var maxConcurrentPasses: Int = 0
        private set

    /** Why the last manual link was dropped (incompatible role); stays visible until a compatible handshake. */
    @Volatile private var roleRejection: EngineStatus? = null

    private var deliveryJob: Job? = null
    private val outboxTrigger = Channel<Unit>(Channel.CONFLATED)

    init {
        transport.localRoleName = _localRole.value.name

        transport.onSosReportReceived = { msg, report, peer -> handleIncomingSosReport(msg, report, peer) }

        // Manual Direct Device Link taps are checked against the last role the target announced.
        transport.connectGuard = { address ->
            val known = knownPeerRoles[address]
            if (known != null && !RoleCompatibility.isCompatible(_localRole.value.name, known)) {
                // The remembered role may be stale (the other phone's user may have switched roles since), so the
                // rejection is one-shot: forget it, and the NEXT tap re-checks over the air.
                knownPeerRoles.remove(address)
                "Cannot connect: that device was a ${roleLabel(known)}, which cannot link with a ${roleLabel(_localRole.value.name)}. " +
                    "If you changed its role, tap it again to re-check."
            } else null
        }

        // ACKs are accepted only from the live, role-announced peer of the expected role. All decisions use the
        // PeerContext of the session that delivered the frame (never "whatever is connected right now").
        transport.onCoordinatorAckReceived = { reportId, fromDevice, peer ->
            val peerRole = peer.role
            val allowed = when (_localRole.value) {
                DeviceRole.SOS_SENDER -> peerRole == DeviceRole.RELAY_DEVICE.name || peerRole == DeviceRole.COORDINATOR.name
                DeviceRole.RELAY_DEVICE -> peerRole == DeviceRole.COORDINATOR.name
                DeviceRole.COORDINATOR -> false
            }
            if (allowed) {
                handleIncomingCoordinatorAck(reportId, fromDevice)
                // Receipt only after the ACK is durably on disk, so a lost receipt can never hide a lost ACK.
                if (_localRole.value == DeviceRole.SOS_SENDER && peerRole == DeviceRole.RELAY_DEVICE.name &&
                    senderStore.getReport(reportId)?.deliveryState == SenderDeliveryState.ACKNOWLEDGED_BY_COORDINATOR &&
                    senderStore.isPersisted()
                ) {
                    peer.sendCoordinatorAckReceipt(reportId)
                }
            }
        }

        transport.onSosAckReceived = { reportId, fromDevice, peer ->
            val peerRole = peer.role
            if (peerRole == DeviceRole.RELAY_DEVICE.name || peerRole == DeviceRole.COORDINATOR.name) {
                handleIncomingSosAck(reportId, fromDevice)
            }
        }

        transport.onCoordinatorAckReceiptReceived = { reportId, fromDevice, peer ->
            handleIncomingCoordinatorAckReceipt(reportId, fromDevice, peer)
        }

        transport.onPeerHandshake = { peer ->
            val role = peer.role
            if (role != null) {
                knownPeerRoles[peer.address] = role
                if (!RoleCompatibility.isCompatible(_localRole.value.name, role)) {
                    // A manual link to an unusable role is dropped with an explicit reason; nothing is queued for it.
                    val rejected = EngineStatus(EngineStatus.Kind.WRONG_PEER_ROLE, "${roleLabel(role)} (${peer.address})")
                    roleRejection = rejected
                    _status.value = rejected
                    transport.releaseConnection()
                } else {
                    roleRejection = null
                    if (_localRole.value == DeviceRole.RELAY_DEVICE && role == DeviceRole.SOS_SENDER.name) {
                        flushPendingDownstreamAcksForPeer(peer.address, null, peer)
                    }
                    // A handshaken link is usable right now: wake the engine. No button needed.
                    notifyOutboxChanged()
                }
            }
        }
        // Wake when the SET of pending work changes (not on every state flip the engine itself makes).
        scope.launch(Dispatchers.IO) {
            senderStore.reportsFlow
                .map { list -> list.filter { it.deliveryState != SenderDeliveryState.ACKNOWLEDGED_BY_COORDINATOR }
                    .map { it.report.reportId to (it.deliveryState == SenderDeliveryState.ACKNOWLEDGED_BY_RELAY) }.toSet() }
                .distinctUntilChanged()
                .collect { notifyOutboxChanged() }
        }
        scope.launch(Dispatchers.IO) {
            relayStore.reportsFlow
                .map { list -> list.filter { !(it.deliveryState == DeliveryState.DELIVERED_TO_COORDINATOR && it.coordinatorAckPropagated) }
                    .map { it.report.reportId to (it.deliveryState == DeliveryState.DELIVERED_TO_COORDINATOR) }.toSet() }
                .distinctUntilChanged()
                .collect { notifyOutboxChanged() }
        }
        // Reliable connected-state event: a link becoming Connected (or its peer role becoming known) triggers the
        // queue. This is what makes forwarding automatic once the user has connected the two phones.
        scope.launch(Dispatchers.IO) {
            combine(transport.connectionState, transport.remotePeerRole) { c, r ->
                if (c is ConnectionState.Connected) c.remoteDeviceAddress to r else null
            }.distinctUntilChanged().collect { notifyOutboxChanged() }
        }
    }

    private fun roleLabel(role: String): String = when (role) {
        DeviceRole.SOS_SENDER.name -> "SOS Sender"
        DeviceRole.RELAY_DEVICE.name -> "Relay"
        DeviceRole.COORDINATOR.name -> "Coordinator"
        else -> role
    }

    /** Notify controller that local outbox/link changed, waking up the delivery loop immediately. */
    fun notifyOutboxChanged() {
        outboxTrigger.trySend(Unit)
    }

    fun setLocalRole(role: DeviceRole) {
        val changed = _localRole.value != role
        _localRole.value = role
        roleRejection = null
        transport.isCoordinatorRole = (role == DeviceRole.COORDINATOR)
        transport.localRoleName = role.name
        if (changed && transport.connectionState.value is ConnectionState.Connected) {
            // The peer validated our OLD role; drop the link so the next handshake announces the new one.
            transport.releaseConnection()
        }
        notifyOutboxChanged()
    }

    fun start() {
        if (_isControllerRunning.value) return
        _isControllerRunning.value = true

        deliveryJob = scope.launch(Dispatchers.IO) {
            while (isActive && _isControllerRunning.value) {
                try {
                    runCycle()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // One failing cycle must never kill the loop; the next cycle retries from durable state.
                    _status.value = EngineStatus(EngineStatus.Kind.WAITING_RETRY, "Internal error; will re-check on the next link event.")
                }
                // Bounded periodic retry: wake immediately if outbox triggers, or after the poll interval
                withTimeoutOrNull(config.cyclePollIntervalMs) { outboxTrigger.receive() }
            }
        }
    }

    fun stop() {
        _isControllerRunning.value = false
        deliveryJob?.cancel()
        deliveryJob = null
        _status.value = EngineStatus(EngineStatus.Kind.STOPPED)
    }

    // ------------------------------------------------------------------------------------------
    // Incoming-message handlers (pure durable-state transitions; role gating happens at the wiring)
    // ------------------------------------------------------------------------------------------

    /**
     * Relay -> Sender direction: (re)send the final COORDINATOR_ACK for every report from this peer that
     * the Relay has delivered but whose receipt has not arrived. Does NOT mark anything propagated: only a
     * receipt from the Sender does that (a successful local socket write proves nothing about the receiver).
     * Returns the number of ACK frames written.
     */
    fun flushPendingDownstreamAcksForPeer(
        peerAddress: String,
        peerName: String? = null,
        peer: BluetoothRfcommTransport.PeerContext? = null
    ): Int {
        val pending = relayStore.getPendingDownstreamAcks(forDeviceAddress = peerAddress, forDeviceName = peerName)
        var written = 0
        for (item in pending) {
            val ok = peer?.sendCoordinatorAck(item.report.reportId) ?: transport.sendCoordinatorAck(item.report.reportId)
            if (ok) written++
        }
        return written
    }

    /**
     * Process a Coordinator ACK. Advances the matching report in the sender or relay durable store.
     * Unknown IDs are ignored.
     */
    fun handleIncomingCoordinatorAck(reportId: String, fromDevice: String) {
        when (_localRole.value) {
            DeviceRole.SOS_SENDER -> {
                if (senderStore.getReport(reportId) != null) {
                    senderStore.markAcknowledgedByCoordinator(reportId)
                }
            }
            DeviceRole.RELAY_DEVICE -> {
                if (relayStore.getAllReports().any { it.report.reportId == reportId }) {
                    relayStore.markDelivered(reportId)
                }
            }
            DeviceRole.COORDINATOR -> Unit // Terminal recipient: does not track downstream ACKs
        }
    }

    /**
     * Receipt from the originating Sender: only now is the final ACK complete for the Relay.
     * Requires a live Sender peer whose address matches the report's recorded source.
     */
    fun handleIncomingCoordinatorAckReceipt(reportId: String, fromDevice: String, peer: BluetoothRfcommTransport.PeerContext) {
        if (_localRole.value != DeviceRole.RELAY_DEVICE) return
        if (peer.role != DeviceRole.SOS_SENDER.name) return
        val item = relayStore.getAllReports().find { it.report.reportId == reportId } ?: return
        if (item.deliveryState != DeliveryState.DELIVERED_TO_COORDINATOR) return
        val source = item.sourceDeviceAddress
        // A receipt only counts from the exact Sender address that originated the report.
        if (source.isNullOrBlank() || !source.equals(peer.address, ignoreCase = true)) return
        relayStore.markCoordinatorAckPropagated(reportId)
    }

    /** Process a Relay SOS ACK. Never regresses a coordinator-acknowledged report. */
    fun handleIncomingSosAck(reportId: String, fromDevice: String) {
        if (_localRole.value == DeviceRole.SOS_SENDER) {
            val reportItem = senderStore.getReport(reportId)
            if (reportItem != null && reportItem.deliveryState != SenderDeliveryState.ACKNOWLEDGED_BY_COORDINATOR) {
                senderStore.markAcknowledgedByRelay(reportId)
            }
        }
    }

    /**
     * Ingestion handler for incoming SOS reports received over RFCOMM.
     * Guarantees durable storage BEFORE ACK dispatch and a fresh ACK on every retransmission.
     */
    suspend fun handleIncomingSosReport(
        msg: SethuMessage,
        report: SosReport,
        peer: BluetoothRfcommTransport.PeerContext? = null
    ) {
        val currentRole = _localRole.value

        // Source address comes from the session that delivered the frame; only fall back to shared state
        // when called without a peer (unit tests / manual ingestion).
        val currentConnection = transport.connectionState.value
        val peerAddress = peer?.address
            ?: if (currentConnection is ConnectionState.Connected) currentConnection.remoteDeviceAddress else ""
        if (peerAddress.isNotBlank()) {
            reportSourcePeerMap[report.reportId] = peerAddress
        }

        when (currentRole) {
            DeviceRole.COORDINATOR -> {
                coordinatorStore.saveReport(report)
                if (coordinatorStore.isDurablyStored(report.reportId)) {
                    peer?.sendCoordinatorAck(report.reportId) ?: transport.sendCoordinatorAck(report.reportId)
                }
            }
            DeviceRole.RELAY_DEVICE -> {
                relayStore.saveReceivedReport(report, peerAddress.takeIf { it.isNotBlank() })
                if (relayStore.isDurablyStored(report.reportId)) {
                    peer?.sendSosAck(report.reportId) ?: transport.sendSosAck(report.reportId)
                }
                // A duplicate may mean the sender never got its final ACK: re-offer it now.
                if (peerAddress.isNotBlank()) {
                    flushPendingDownstreamAcksForPeer(peerAddress, msg.senderDeviceName, peer)
                }
            }
            DeviceRole.SOS_SENDER -> Unit // Sender does not ingest reports from other peers
        }
    }

    // ------------------------------------------------------------------------------------------
    // Delivery cycle (link-driven; never dials, never disconnects)
    // ------------------------------------------------------------------------------------------

    private fun setStatus(kind: EngineStatus.Kind, detail: String = "") {
        _status.value = EngineStatus(kind, detail)
    }

    private suspend fun runCycle() {
        if (!transport.isBluetoothSupported()) {
            setStatus(EngineStatus.Kind.BLUETOOTH_UNSUPPORTED)
            return
        }
        if (!transport.isBluetoothEnabled()) {
            setStatus(EngineStatus.Kind.BLUETOOTH_OFF)
            return
        }
        if (!transport.hasBluetoothPermission()) {
            setStatus(EngineStatus.Kind.PERMISSION_REQUIRED)
            return
        }
        roleRejection?.let { rejected ->
            if (transport.connectionState.value !is ConnectionState.Connected) {
                _status.value = rejected
                return
            }
        }
        val running = activePasses.incrementAndGet()
        if (running > maxConcurrentPasses) maxConcurrentPasses = running
        try {
            when (_localRole.value) {
                DeviceRole.SOS_SENDER -> processSenderLink()
                DeviceRole.RELAY_DEVICE -> processRelayLink()
                DeviceRole.COORDINATOR -> setStatus(EngineStatus.Kind.LISTENING)
            }
        } finally {
            activePasses.decrementAndGet()
        }
    }

    /** (address, role) of the current link if it is established and the peer announced one of [validRoles]. */
    private fun usableLink(validRoles: Set<String>): Pair<String, String>? {
        val conn = transport.connectionState.value as? ConnectionState.Connected ?: return null
        val role = transport.remotePeerRole.value ?: return null
        return if (role in validRoles) conn.remoteDeviceAddress to role else null
    }

    /** Counts a transmission attempt for [reportId] on the current connection; false once the cap is reached. */
    private fun tryClaimAttempt(sessionId: Long, reportId: String): Boolean {
        // Drop bookkeeping of earlier connections so the map stays bounded.
        attempts.keys.removeAll { !it.startsWith("$sessionId:") }
        val key = "$sessionId:$reportId"
        val n = attempts.merge(key, 1) { a, b -> a + b } ?: 1
        return n <= config.maxAttemptsPerLink
    }

    /**
     * Sender: when a Relay/Coordinator link exists, send every pending report and await the matching ACK.
     * Receiving the final Coordinator ACK later (via the Relay) is handled by the ACK callbacks.
     */
    private suspend fun processSenderLink() {
        val pending = senderStore.getPendingReports()
        if (pending.isEmpty()) {
            setStatus(EngineStatus.Kind.IDLE)
            return
        }
        val link = usableLink(setOf(DeviceRole.RELAY_DEVICE.name, DeviceRole.COORDINATOR.name))
        if (link == null) {
            val conn = transport.connectionState.value
            if (conn is ConnectionState.Connected) setStatus(EngineStatus.Kind.HANDSHAKING, conn.remoteDeviceName)
            else setStatus(EngineStatus.Kind.WAITING_RETRY, "SOS saved on this phone — connect to the Relay in Direct Device Link to send it")
            return
        }
        val peerRole = link.second
        val sessionId = transport.currentSessionId()
        val deviceName = (transport.connectionState.value as? ConnectionState.Connected)?.remoteDeviceName ?: link.first
        val expectedAckType =
            if (peerRole == DeviceRole.COORDINATOR.name) SethuMessage.TYPE_COORDINATOR_ACK else SethuMessage.TYPE_SOS_ACK

        setStatus(EngineStatus.Kind.DELIVERING, deviceName)
        for (item in senderStore.getPendingReports()) {
            if (!_isControllerRunning.value || !scope.isActive) break
            val report = item.report
            if (!tryClaimAttempt(sessionId, report.reportId)) continue
            transmissionsStarted.incrementAndGet()
            senderStore.markTransmitted(report.reportId)
            val acked = transport.sendSosReportAwaitingAck(report, expectedAckType, config.ackTimeoutMs, sessionId)
            if (acked) {
                if (peerRole == DeviceRole.COORDINATOR.name) senderStore.markAcknowledgedByCoordinator(report.reportId)
                else senderStore.markAcknowledgedByRelay(report.reportId)
            } else {
                val stillLinked = transport.currentSessionId() == sessionId
                senderStore.markTransmissionFailed(report.reportId, if (stillLinked) "Peer ACK timeout" else "Link lost during transmission")
                if (!stillLinked) break
            }
        }
        if (senderStore.getPendingReports().isEmpty()) setStatus(EngineStatus.Kind.IDLE)
        else setStatus(EngineStatus.Kind.WAITING_RETRY, "SOS saved — waiting for the peer's acknowledgement")
    }

    /**
     * Relay: when a Coordinator link exists (manually established by the user), forward every pending report
     * automatically. DELIVERED is recorded only after that Coordinator's ACK for the same report ID.
     */
    private suspend fun processRelayLink() {
        val pending = relayStore.getPendingReports()
        if (pending.isEmpty()) {
            setStatus(EngineStatus.Kind.LISTENING)
            return
        }
        val link = usableLink(setOf(DeviceRole.COORDINATOR.name))
        if (link == null) {
            val conn = transport.connectionState.value
            if (conn is ConnectionState.Connected && transport.remotePeerRole.value == null) {
                setStatus(EngineStatus.Kind.HANDSHAKING, conn.remoteDeviceName)
            } else {
                setStatus(EngineStatus.Kind.WAITING_RETRY, "SOS saved on this phone, pending Coordinator delivery — connect to the Coordinator in Direct Device Link")
            }
            return
        }
        val sessionId = transport.currentSessionId()
        val deviceName = (transport.connectionState.value as? ConnectionState.Connected)?.remoteDeviceName ?: link.first
        setStatus(EngineStatus.Kind.DELIVERING, deviceName)

        for (item in relayStore.getPendingReports()) {
            if (!_isControllerRunning.value || !scope.isActive) break
            val source = item.sourceDeviceAddress ?: reportSourcePeerMap[item.report.reportId]
            // Loop prevention: never route a report back to the peer it came from.
            if (source != null && source.equals(link.first, ignoreCase = true)) continue
            if (!tryClaimAttempt(sessionId, item.report.reportId)) continue

            transmissionsStarted.incrementAndGet()
            relayStore.markForwarded(item.report.reportId)
            val acked = transport.sendSosReportAwaitingAck(
                report = item.report,
                expectedAckType = SethuMessage.TYPE_COORDINATOR_ACK,
                timeoutMs = config.ackTimeoutMs,
                requiredSessionId = sessionId
            )
            if (acked) {
                relayStore.markDelivered(item.report.reportId)
            } else {
                val stillLinked = transport.currentSessionId() == sessionId
                relayStore.markForwardFailed(
                    item.report.reportId,
                    if (stillLinked) "Coordinator ACK timed out" else "Link lost before Coordinator ACK"
                )
                if (!stillLinked) break
            }
        }
        if (relayStore.getPendingReports().isEmpty()) setStatus(EngineStatus.Kind.LISTENING)
        else setStatus(EngineStatus.Kind.WAITING_RETRY, "Forwarding not yet acknowledged by the Coordinator — SOS stays queued")
    }

    // ------------------------------------------------------------------------------------------
    // Peer role knowledge
    // ------------------------------------------------------------------------------------------

    fun recordPeerRole(address: String, role: String) {
        knownPeerRoles[address] = role
    }

    fun getKnownPeerRole(address: String): String? = knownPeerRoles[address]
}
