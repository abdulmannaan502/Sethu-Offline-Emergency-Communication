package com.sethu.emergency.comm.bluetooth

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import com.sethu.emergency.comm.ConnectionState
import com.sethu.emergency.comm.RoleCompatibility
import com.sethu.emergency.comm.ServerStartResult
import com.sethu.emergency.comm.ServerState
import com.sethu.emergency.comm.TransferStatus
import com.sethu.emergency.comm.TransportService
import com.sethu.emergency.comm.model.SethuMessage
import com.sethu.emergency.comm.model.SosReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Bluetooth Classic RFCOMM implementation of [TransportService].
 *
 * Ownership rules (see docs/RELEASE_CANDIDATE_REPORT.md):
 * - Exactly one [Session] (one live socket) exists at a time. A session owns its socket, its write lock,
 *   its announced-role flag and its peer role. Nothing outside the session may mutate those.
 * - [connectionMutex] guards only short, non-blocking state transitions (never socket I/O or accept()).
 * - Every reader coroutine acts on behalf of exactly one session and may mutate shared state only while
 *   `session === itsOwnSession`, so a stale reader can never clear or overwrite a newer connection.
 * - Nothing listens or dials on its own. The user starts the single server loop with [startServer] (Start Server);
 *   it then goes back to listening after every session ends until [stopServer]. It parks (no polling, no busy
 *   loop) when Bluetooth is off or a permission is missing and is woken by Bluetooth-ON/permission changes.
 *   Outbound connections happen only through [connectToDevice]/[connect] on explicit user action.
 * - Outbound connects are bounded by [connectTimeoutMs] through a watchdog that aborts the blocking connect.
 *
 * Security note: RFCOMM pairing is not application-level authentication. Role announcements and ACKs are
 * validated for shape and ordering only; they are not cryptographically authenticated.
 */
class BluetoothRfcommTransport(
    private val context: Context? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val adapter: RfcommAdapter? = context?.let { AndroidRfcommAdapter(it) },
    private val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    private val listenRetryDelayMs: Long = DEFAULT_LISTEN_RETRY_MS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : TransportService {

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000L
        const val DEFAULT_LISTEN_RETRY_MS = 2_000L
        private const val MAX_LISTEN_FAILURES = 5
        private const val MAX_RETAINED_MESSAGES = 500
        private const val MAX_DEDUP_IDS = 2_000
        private const val MAX_ID_LENGTH = 128

        const val MSG_PERMISSION_REQUIRED =
            "Bluetooth permission required. Grant Nearby devices permission to enable emergency links."
        const val MSG_BLUETOOTH_DISABLED = "Bluetooth is disabled. Please turn it on in Settings."
        const val MSG_UNSUPPORTED = "Bluetooth is not supported on this device"

        /** Role names exchanged on the wire; must match com.sethu.emergency.ui.screens.DeviceRole names. */
        val KNOWN_ROLES: Set<String> = setOf("SOS_SENDER", "RELAY_DEVICE", "COORDINATOR")

        val SPP_UUID = AndroidRfcommAdapter.SPP_UUID
    }

    internal class Session(val channel: RfcommChannel, val isServer: Boolean, val id: Long) {
        val remoteAddress: String = channel.remoteAddress
        /** ACKs received on THIS session from a peer whose announced role may legitimately send them. */
        val sosAcks = MutableStateFlow<Set<String>>(emptySet())
        val coordinatorAcks = MutableStateFlow<Set<String>>(emptySet())
        val writeLock = Any()
        val announced = AtomicBoolean(false)
        val closed = AtomicBoolean(false)
        @Volatile var peerRole: String? = null
        @Volatile var lastActivity: Long = System.currentTimeMillis()
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.NotConnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _transferStatus = MutableStateFlow<TransferStatus>(TransferStatus.Idle)
    override val transferStatus: StateFlow<TransferStatus> = _transferStatus.asStateFlow()

    private val _receivedMessages = MutableStateFlow<List<SethuMessage>>(emptyList())
    override val receivedMessages: StateFlow<List<SethuMessage>> = _receivedMessages.asStateFlow()

    private val _sentMessages = MutableStateFlow<List<SethuMessage>>(emptyList())
    override val sentMessages: StateFlow<List<SethuMessage>> = _sentMessages.asStateFlow()

    private val _acknowledgedReportIds = MutableStateFlow<Set<String>>(emptySet())
    override val acknowledgedReportIds: StateFlow<Set<String>> = _acknowledgedReportIds.asStateFlow()

    private val _coordinatorAcknowledgedReportIds = MutableStateFlow<Set<String>>(emptySet())
    val coordinatorAcknowledgedReportIds: StateFlow<Set<String>> = _coordinatorAcknowledgedReportIds.asStateFlow()

    /** Role announced by the peer of the CURRENT session only; null when not connected or not yet announced. */
    private val _remotePeerRole = MutableStateFlow<String?>(null)
    val remotePeerRole: StateFlow<String?> = _remotePeerRole.asStateFlow()

    /** Flag indicating whether the local role is Coordinator, determining which ACK type is sent upon SOS receipt. */
    @Volatile var isCoordinatorRole: Boolean = false

    /** The active local device role name (SOS_SENDER, RELAY_DEVICE, COORDINATOR) */
    @Volatile var localRoleName: String = "SOS_SENDER"

    /**
     * True only after the user tapped Start Server (or [startServer] was called). The transport never opens a
     * listener on its own; nothing is listening after app start until this is set.
     */
    @Volatile var autoServerListening: Boolean = false

    private val _serverState = MutableStateFlow<ServerState>(ServerState.Stopped)
    /** Real listener state: Stopped / Starting / Listening / Busy / Failed(reason). */
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    /**
     * Optional pre-flight check for user-initiated outbound connections: returns a human-readable rejection
     * reason (e.g. the peer is known to have an incompatible role) or null to allow. Installed by the controller.
     */
    @Volatile var connectGuard: ((address: String) -> String?)? = null

    private val sessionCounter = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var connectingAddress: String? = null   // guarded by connectionMutex (writes)
    /** Peer of a user tap whose connect coroutine has been launched but may not have reached [connect] yet. */
    @Volatile private var tappedAddress: String? = null

    /**
     * The peer of ONE specific session. Everything a message handler needs (who sent it, which role it announced)
     * and every reply it may send is bound to that session, so a reply can never leak onto a newer connection
     * and a handler never reads "the current peer" from shared state that may already have moved on.
     */
    inner class PeerContext internal constructor(private val s: Session) {
        val address: String get() = s.remoteAddress
        val role: String? get() = s.peerRole
        /** Identifies the physical connection; changes on every new connection, even to the same peer. */
        val sessionId: Long get() = s.id
        fun sendSosAck(reportId: String): Boolean = sendControlOn(s, SethuMessage.TYPE_SOS_ACK, reportId)
        fun sendCoordinatorAck(reportId: String): Boolean = sendControlOn(s, SethuMessage.TYPE_COORDINATOR_ACK, reportId)
        fun sendCoordinatorAckReceipt(reportId: String): Boolean =
            sendControlOn(s, SethuMessage.TYPE_COORDINATOR_ACK_RECEIPT, reportId)
    }

    /** Callback invoked whenever a valid SOS report is received. */
    var onSosReportReceived: (suspend (SethuMessage, SosReport, PeerContext) -> Unit)? = null

    /** Callback invoked whenever a valid TYPE_COORDINATOR_ACK is received. */
    var onCoordinatorAckReceived: (suspend (reportId: String, fromDevice: String, peer: PeerContext) -> Unit)? = null

    /** Callback invoked whenever a valid TYPE_SOS_ACK is received. */
    var onSosAckReceived: (suspend (reportId: String, fromDevice: String, peer: PeerContext) -> Unit)? = null

    /** Callback invoked whenever a valid TYPE_COORDINATOR_ACK_RECEIPT is received. */
    var onCoordinatorAckReceiptReceived: (suspend (reportId: String, fromDevice: String, peer: PeerContext) -> Unit)? = null

    /** Callback invoked whenever a peer role is announced. */
    var onRoleAnnounced: (suspend (role: String, remoteDeviceAddress: String) -> Unit)? = null

    /** Like [onRoleAnnounced] but with the session-bound [PeerContext], so replies target exactly that peer. */
    var onPeerHandshake: (suspend (PeerContext) -> Unit)? = null

    // Dedup of non-SOS messages (bounded)
    private val processedMessageIds: MutableSet<String> = Collections.synchronizedSet(
        Collections.newSetFromMap(object : LinkedHashMap<String, Boolean>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
                size > MAX_DEDUP_IDS
        })
    )

    // Guards short state transitions only; never held across accept()/connect()/read()/write().
    private val connectionMutex = Mutex()
    @Volatile private var session: Session? = null
    @Volatile private var listener: RfcommListener? = null
    private var connecting = false          // guarded by connectionMutex
    @Volatile private var connectGeneration = 0L      // written under connectionMutex

    private val serverWake = Channel<Unit>(Channel.CONFLATED)
    private var serverJob: Job? = null
    private var clientJob: Job? = null

    // ---------------------------------------------------------------------------------------------
    // Bluetooth adapter state / receiver
    // ---------------------------------------------------------------------------------------------

    private var isStateReceiverRegistered = false
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(recvContext: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> onBluetoothDisabled()
                    BluetoothAdapter.STATE_ON -> onBluetoothEnabled()
                }
            }
        }
    }

    /** Registers the adapter-state receiver. Returns false (and reports it) if registration failed. */
    fun registerBluetoothReceiver(ctx: Context): Boolean {
        if (isStateReceiverRegistered) return true
        return try {
            ContextCompat.registerReceiver(
                ctx,
                bluetoothStateReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            isStateReceiverRegistered = true
            true
        } catch (e: Exception) {
            _transferStatus.value = TransferStatus.Failed("Could not monitor Bluetooth on/off state")
            false
        }
    }

    fun unregisterBluetoothReceiver(ctx: Context) {
        if (!isStateReceiverRegistered) return
        try {
            ctx.unregisterReceiver(bluetoothStateReceiver)
        } catch (_: IllegalArgumentException) {
            // Already unregistered
        }
        isStateReceiverRegistered = false
    }

    suspend fun handleBluetoothDisabled() {
        connectionMutex.withLock {
            abortOutboundLocked()
            closeSessionLocked(session)
            closeListener()
            _remotePeerRole.value = null
            _connectionState.value = ConnectionState.Failed(MSG_BLUETOOTH_DISABLED)
            if (autoServerListening) _serverState.value = ServerState.Failed(MSG_BLUETOOTH_DISABLED)
        }
    }

    suspend fun handleBluetoothEnabled() {
        val a = adapter
        if (a != null && !a.hasPermission()) {
            connectionMutex.withLock {
                if (session == null) _connectionState.value = ConnectionState.Failed(MSG_PERMISSION_REQUIRED)
            }
            return
        }
        connectionMutex.withLock {
            if (_connectionState.value is ConnectionState.Failed) {
                _connectionState.value = ConnectionState.NotConnected
            }
        }
        ensureListening()
    }

    fun onBluetoothDisabled() {
        scope.launch(ioDispatcher) { handleBluetoothDisabled() }
    }

    fun onBluetoothEnabled() {
        scope.launch(ioDispatcher) { handleBluetoothEnabled() }
    }

    /** Call after runtime permissions change so a previously parked server loop resumes. */
    fun onPermissionsChanged() {
        if (adapter?.hasPermission() == true && _connectionState.value is ConnectionState.Failed) {
            val s = _connectionState.value as ConnectionState.Failed
            if (s.reason == MSG_PERMISSION_REQUIRED) _connectionState.value = ConnectionState.NotConnected
        }
        ensureListening()
    }

    fun getLocalDeviceName(): String = adapter?.localName() ?: Build.MODEL ?: "Sethu Device"

    fun isBluetoothSupported(): Boolean = adapter?.isSupported == true

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled() == true

    fun hasBluetoothPermission(): Boolean = adapter?.hasPermission() == true

    /** Bonded devices; empty when none are paired or permission is missing (check [hasBluetoothPermission]). */
    fun getBondedDevices(): List<PeerDevice> {
        return try {
            adapter?.bondedDevices() ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Server (listening) lifecycle
    // ---------------------------------------------------------------------------------------------

    /** Kept for existing callers; identical to [startServer] but without the result. */
    fun startAutoServerListening() {
        startServer()
    }

    fun stopAutoServerListening() {
        stopServer()
    }

    /**
     * User-initiated "Start Server". Validates support, Bluetooth state and permission synchronously, then starts
     * exactly one listener loop (repeated calls only wake the existing loop). The asynchronous outcome
     * (Listening or a specific failure) is published on [serverState].
     */
    @Synchronized
    fun startServer(): ServerStartResult {
        val a = adapter
        if (a == null || !a.isSupported) {
            _serverState.value = ServerState.Failed(MSG_UNSUPPORTED)
            _connectionState.value = ConnectionState.Failed(MSG_UNSUPPORTED)
            return ServerStartResult.BLUETOOTH_UNSUPPORTED
        }
        if (!a.isEnabled()) {
            _serverState.value = ServerState.Failed(MSG_BLUETOOTH_DISABLED)
            setStateIfIdle(ConnectionState.Failed(MSG_BLUETOOTH_DISABLED))
            return ServerStartResult.BLUETOOTH_DISABLED
        }
        if (!a.hasPermission()) {
            _serverState.value = ServerState.Failed(MSG_PERMISSION_REQUIRED)
            setStateIfIdle(ConnectionState.Failed(MSG_PERMISSION_REQUIRED))
            return ServerStartResult.PERMISSION_MISSING
        }
        autoServerListening = true
        val job = serverJob
        if (job != null && job.isActive) {
            if (_serverState.value is ServerState.Failed) {
                _serverState.value = ServerState.Starting
            }
            serverWake.trySend(Unit)
            return ServerStartResult.ALREADY_RUNNING
        }
        _serverState.value = ServerState.Starting
        serverJob = scope.launch(ioDispatcher) { serverLoop() }
        return ServerStartResult.STARTED
    }

    /**
     * User-initiated "Stop Server": closes the listener and ends the listen loop. An already-established link is
     * NOT touched (closing a server socket never closes accepted sockets); use [disconnect] to drop the link.
     */
    @Synchronized
    fun stopServer() {
        autoServerListening = false
        serverJob?.cancel()
        serverJob = null
        closeListener()
        _serverState.value = ServerState.Stopped
        if (_connectionState.value is ConnectionState.Listening) {
            _connectionState.value = ConnectionState.NotConnected
        }
        serverWake.trySend(Unit)
    }

    /** Idempotent: starts the single server loop if it is not running, otherwise just wakes it. */
    override fun startHostingServer() {
        startServer()
    }

    /** Ensures the device is connectable (when auto-listening is enabled). Cheap; safe to call every cycle. */
    fun ensureListening() {
        if (autoServerListening && adapter != null) startHostingServer()
    }

    private fun setStateIfIdle(state: ConnectionState) {
        val cur = _connectionState.value
        if (cur !is ConnectionState.Connected && cur !is ConnectionState.Connecting) _connectionState.value = state
    }

    private suspend fun awaitServerWake() {
        serverWake.receive()
    }

    private fun failServer(reason: String) {
        _serverState.value = ServerState.Failed(reason)
        setStateIfIdle(ConnectionState.Failed(reason))
    }

    private suspend fun serverLoop() {
        val a = adapter ?: return
        var consecutiveFailures = 0
        while (currentCoroutineContext().isActive && autoServerListening) {
            if (!a.isEnabled()) {
                failServer(MSG_BLUETOOTH_DISABLED)
                awaitServerWake()
                continue
            }
            if (!a.hasPermission()) {
                failServer(MSG_PERMISSION_REQUIRED)
                awaitServerWake()
                continue
            }
            // Park (without polling) while a session/outbound attempt owns the radio.
            if (_connectionState.value is ConnectionState.Connected || _connectionState.value is ConnectionState.Connecting) {
                _serverState.value = ServerState.Busy
            }
            _connectionState.first { it !is ConnectionState.Connected && it !is ConnectionState.Connecting }

            var server: RfcommListener? = null
            try {
                a.cancelDiscovery()
                server = try {
                    a.listen()
                } catch (e: SecurityException) {
                    throw e
                } catch (e: IOException) {
                    // Could not open the listener at all: report it and wait for the user/Bluetooth/permission
                    // to change instead of retrying in a tight loop.
                    failServer("Could not start Bluetooth server: ${e.localizedMessage ?: "unknown error"}")
                    awaitServerWake()
                    continue
                }
                val installed = connectionMutex.withLock {
                    if (session != null || connecting) {
                        false
                    } else {
                        listener = server
                        _serverState.value = ServerState.Listening(AndroidRfcommAdapter.SERVICE_NAME)
                        if (_connectionState.value !is ConnectionState.Listening) {
                            _connectionState.value = ConnectionState.Listening("SethuEmergencyRFCOMM")
                        }
                        true
                    }
                }
                if (!installed) {
                    server.close()
                    continue
                }
                val channel = server.accept() // blocks; no lock held
                consecutiveFailures = 0
                acceptInbound(channel)
            } catch (e: SecurityException) {
                failServer(MSG_PERMISSION_REQUIRED)
                awaitServerWake()
            } catch (e: IOException) {
                // Listener closed (outbound attempt, Bluetooth off, stop) or a transient stack failure.
                val cur = _connectionState.value
                if (currentCoroutineContext().isActive && autoServerListening &&
                    cur !is ConnectionState.Connected && cur !is ConnectionState.Connecting
                ) {
                    if (cur is ConnectionState.Listening) _connectionState.value = ConnectionState.NotConnected
                    if (++consecutiveFailures >= MAX_LISTEN_FAILURES) {
                        failServer("Bluetooth server stopped unexpectedly: ${e.localizedMessage ?: "listener closed"}")
                        consecutiveFailures = 0
                        awaitServerWake()
                    } else {
                        delay(listenRetryDelayMs)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failServer("Bluetooth server error: ${e.javaClass.simpleName}: ${e.localizedMessage ?: ""}".trim())
                awaitServerWake()
            } finally {
                try { server?.close() } catch (_: IOException) {}
                if (listener === server) listener = null
            }
        }
    }

    private fun closeListener() {
        val l = listener
        listener = null
        try { l?.close() } catch (_: IOException) {}
        if (l != null && autoServerListening && _serverState.value is ServerState.Listening) {
            _serverState.value = ServerState.Busy
        }
    }

    private suspend fun acceptInbound(channel: RfcommChannel) {
        connectionMutex.withLock {
            closeListener()
            if (connecting) {
                // Inbound wins over an in-flight outbound attempt (simultaneous-open arbitration).
                connectGeneration++
                connecting = false
                connectingAddress = null
                adapter?.abortConnect()
            }
            val cur = session
            if (cur != null && !cur.closed.get()) {
                if (cur.remoteAddress.equals(channel.remoteAddress, ignoreCase = true)) {
                    closeSessionLocked(cur) // same peer reconnecting: its old socket is dead
                } else {
                    try { channel.close() } catch (_: IOException) {}
                    return
                }
            }
            attachLocked(channel, isServer = true)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Client (outbound) lifecycle
    // ---------------------------------------------------------------------------------------------

    /**
     * User-initiated connect (Direct Device Link). Rejected up front when the peer is known to be incompatible,
     * and ignored when an attempt to / a link with the same peer already exists, so taps never stack attempts.
     */
    @Synchronized
    override fun connectToDevice(deviceAddress: String, deviceName: String) {
        connectGuard?.invoke(deviceAddress)?.let { reason ->
            _transferStatus.value = TransferStatus.Failed(reason)
            return
        }
        if (connectingAddress.equals(deviceAddress, ignoreCase = true) ||
            tappedAddress.equals(deviceAddress, ignoreCase = true)
        ) return
        val cur = session
        if (cur != null && !cur.closed.get() && cur.remoteAddress.equals(deviceAddress, ignoreCase = true)) return
        clientJob?.cancel()
        tappedAddress = deviceAddress
        clientJob = scope.launch(ioDispatcher) {
            try {
                connect(deviceAddress, deviceName)
            } finally {
                if (tappedAddress.equals(deviceAddress, ignoreCase = true)) tappedAddress = null
            }
        }
    }

    /**
     * Establishes an outbound RFCOMM session. Returns true iff a session to [address] is live on return.
     * Bounded by [connectTimeoutMs]; cancellation aborts the blocking connect.
     */
    suspend fun connect(address: String, name: String): Boolean {
        val a = adapter
        if (a == null) {
            _connectionState.value = ConnectionState.Failed(MSG_UNSUPPORTED)
            return false
        }
        if (!a.isEnabled()) {
            _connectionState.value = ConnectionState.Failed(MSG_BLUETOOTH_DISABLED)
            return false
        }
        if (!a.hasPermission()) {
            _connectionState.value = ConnectionState.Failed(MSG_PERMISSION_REQUIRED)
            return false
        }

        var myGeneration = 0L
        var alreadyConnected = false
        connectionMutex.withLock {
            val cur = session
            if (cur != null && !cur.closed.get() && cur.remoteAddress.equals(address, ignoreCase = true)) {
                alreadyConnected = true
                return@withLock
            }
            if (connecting) a.abortConnect() // supersede an older in-flight attempt
            closeSessionLocked(cur)
            closeListener()
            connecting = true
            connectingAddress = address
            myGeneration = ++connectGeneration
            _connectionState.value = ConnectionState.Connecting(name)
        }
        if (alreadyConnected) return true

        // The blocking connect + commit run in a transport-owned coroutine so that cancelling the caller
        // (which cannot interrupt a blocking Bluetooth connect) can never leak a socket or leave state stale.
        val callerCancelled = AtomicBoolean(false)
        val worker = scope.async(ioDispatcher) {
            val watchdog = launch {
                delay(connectTimeoutMs)
                if (connectGeneration == myGeneration) a.abortConnect()
            }
            var channel: RfcommChannel? = null
            var error: Throwable? = null
            try {
                a.cancelDiscovery()
                channel = a.connect(address)
            } catch (e: Exception) {
                error = e
            } finally {
                watchdog.cancel()
            }
            withContext(NonCancellable) {
                connectionMutex.withLock {
                    if (connectGeneration != myGeneration) {
                        // Superseded by an inbound connection, a newer connect, or disconnect().
                        try { channel?.close() } catch (_: IOException) {}
                        return@withLock false
                    }
                    connecting = false
                    connectingAddress = null
                    if (channel != null && error == null && !callerCancelled.get()) {
                        attachLocked(channel, isServer = false)
                        true
                    } else {
                        try { channel?.close() } catch (_: IOException) {}
                        _connectionState.value = if (callerCancelled.get()) {
                            ConnectionState.NotConnected
                        } else {
                            ConnectionState.Failed("Connection to $name failed: ${describe(error)}")
                        }
                        // The listener (if the user started one) resumes right after this and replaces the link
                        // state, so keep the failure visible in the transfer status as well.
                        if (!callerCancelled.get()) {
                            _transferStatus.value = TransferStatus.Failed("Connection to $name failed: ${describe(error)}")
                        }
                        serverWake.trySend(Unit)
                        false
                    }
                }
            }
        }
        try {
            return worker.await()
        } catch (e: CancellationException) {
            callerCancelled.set(true)
            if (connectGeneration == myGeneration) a.abortConnect()
            throw e
        }
    }

    private fun describe(error: Throwable?): String = when (error) {
        null -> "unknown error"
        is SecurityException -> "permission denied"
        else -> error.localizedMessage?.take(80) ?: "connection refused or timed out"
    }

    // ---------------------------------------------------------------------------------------------
    // Session management
    // ---------------------------------------------------------------------------------------------

    /** Must be called with [connectionMutex] held. */
    private fun attachLocked(channel: RfcommChannel, isServer: Boolean) {
        val s = Session(channel, isServer, sessionCounter.incrementAndGet())
        session = s
        _remotePeerRole.value = null
        _connectionState.value = ConnectionState.Connected(
            remoteDeviceName = channel.remoteName?.takeIf { it.isNotBlank() } ?: s.remoteAddress.ifBlank { "Peer Device" },
            remoteDeviceAddress = s.remoteAddress,
            isServer = isServer
        )
        scope.launch(ioDispatcher) { readLoop(s) }
    }

    /** Must be called with [connectionMutex] held. */
    private fun closeSessionLocked(s: Session?) {
        if (s == null) return
        if (s.closed.compareAndSet(false, true)) {
            try { s.channel.close() } catch (_: IOException) {}
        }
        if (session === s) {
            session = null
            _remotePeerRole.value = null
            if (_connectionState.value is ConnectionState.Connected) {
                _connectionState.value = ConnectionState.NotConnected
            }
        }
    }

    /** Must be called with [connectionMutex] held. */
    private fun abortOutboundLocked() {
        if (connecting) {
            connectGeneration++
            connecting = false
            connectingAddress = null
            adapter?.abortConnect()
        }
    }

    private suspend fun endSession(s: Session) {
        connectionMutex.withLock { closeSessionLocked(s) }
    }

    /** Closes the socket of [s] without touching shared state; its reader then finalizes via [endSession]. */
    private fun failSession(s: Session) {
        if (s.closed.compareAndSet(false, true)) {
            try { s.channel.close() } catch (_: IOException) {}
        }
    }

    /** Id of the live session, or 0 when there is none. Changes with every new physical connection. */
    fun currentSessionId(): Long = liveSession()?.id ?: 0L

    /** Milliseconds since the current session last sent or received a frame; null if there is no live session. */
    fun sessionIdleMs(): Long? = liveSession()?.let { System.currentTimeMillis() - it.lastActivity }

    private fun liveSession(): Session? {
        val s = session ?: return null
        return if (!s.closed.get() && _connectionState.value is ConnectionState.Connected) s else null
    }

    private suspend fun readLoop(s: Session) {
        try {
            announceRole(s)
            val reader = BoundedLineReader(s.channel.input)
            while (true) {
                when (val frame = reader.readFrame()) {
                    is BoundedLineReader.Frame.EndOfStream -> break
                    is BoundedLineReader.Frame.Oversized ->
                        if (session === s) _transferStatus.value = TransferStatus.Failed("Dropped oversized message from peer")
                    is BoundedLineReader.Frame.Line -> if (frame.text.isNotBlank()) {
                        s.lastActivity = System.currentTimeMillis()
                        handleLine(s, frame.text)
                    }
                }
            }
        } catch (e: IOException) {
            // Socket closed or link lost
        } finally {
            withContext(NonCancellable) { endSession(s) }
        }
    }

    private suspend fun handleLine(s: Session, line: String) {
        val message = SethuMessage.fromJson(line)
        if (message == null) {
            if (session === s) _transferStatus.value = TransferStatus.Failed("Dropped malformed message from peer")
            return
        }
        if (session !== s) return // stale reader: never act for a replaced connection
        try {
            when (message.messageType) {
                SethuMessage.TYPE_SOS_REPORT -> {
                    val parsed = SosReport.fromJson(message.payload)
                    if (parsed == null) {
                        _transferStatus.value = TransferStatus.Failed("Rejected malformed SOS report from peer")
                        return
                    }
                    val report = parsed.copy(senderDeviceName = message.senderDeviceName)
                    _receivedMessages.value = capped(_receivedMessages.value, message)
                    _transferStatus.value = TransferStatus.MessageReceived(message.messageId, message.senderDeviceName)
                    // Retransmissions are always passed to ingestion so a fresh ACK can be dispatched.
                    onSosReportReceived?.invoke(message, report, PeerContext(s))
                }
                SethuMessage.TYPE_ROLE_ANNOUNCE -> {
                    val role = message.payload.trim()
                    if (role !in KNOWN_ROLES) {
                        _transferStatus.value = TransferStatus.Failed("Ignored unknown peer role")
                        return
                    }
                    s.peerRole = role
                    _remotePeerRole.value = role
                    announceRole(s) // reciprocal handshake (idempotent per session)
                    onRoleAnnounced?.invoke(role, s.remoteAddress)
                    onPeerHandshake?.invoke(PeerContext(s))
                }
                SethuMessage.TYPE_SOS_ACK -> {
                    val id = ackId(message) ?: return
                    if (s.peerRole == null) return // ACKs are only meaningful from a role-announced peer
                    _acknowledgedReportIds.value = _acknowledgedReportIds.value + id
                    if (s.peerRole == "RELAY_DEVICE" || s.peerRole == "COORDINATOR") s.sosAcks.value = s.sosAcks.value + id
                    _receivedMessages.value = capped(_receivedMessages.value, message)
                    _transferStatus.value = TransferStatus.ReportAcknowledged(id, message.senderDeviceName)
                    onSosAckReceived?.invoke(id, message.senderDeviceName, PeerContext(s))
                }
                SethuMessage.TYPE_COORDINATOR_ACK -> {
                    val id = ackId(message) ?: return
                    if (s.peerRole == null) return
                    _coordinatorAcknowledgedReportIds.value = _coordinatorAcknowledgedReportIds.value + id
                    // Only an actual Coordinator can complete a Coordinator-stage wait on this session.
                    if (s.peerRole == "COORDINATOR") s.coordinatorAcks.value = s.coordinatorAcks.value + id
                    _receivedMessages.value = capped(_receivedMessages.value, message)
                    _transferStatus.value = TransferStatus.ReportAcknowledged(id, message.senderDeviceName)
                    onCoordinatorAckReceived?.invoke(id, message.senderDeviceName, PeerContext(s))
                }
                SethuMessage.TYPE_COORDINATOR_ACK_RECEIPT -> {
                    val id = ackId(message) ?: return
                    if (s.peerRole == null) return
                    onCoordinatorAckReceiptReceived?.invoke(id, message.senderDeviceName, PeerContext(s))
                }
                else -> {
                    if (processedMessageIds.add(message.messageId)) {
                        _receivedMessages.value = capped(_receivedMessages.value, message)
                        _transferStatus.value = TransferStatus.MessageReceived(message.messageId, message.senderDeviceName)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            // A handler failure must neither kill the reader silently nor be reported as success.
            _transferStatus.value = TransferStatus.Failed("Failed to process ${message.messageType} from peer")
        }
    }

    private fun ackId(message: SethuMessage): String? {
        val id = message.payload.trim()
        return if (id.isEmpty() || id.length > MAX_ID_LENGTH) null else id
    }

    private fun capped(list: List<SethuMessage>, message: SethuMessage): List<SethuMessage> {
        val updated = list + message
        return if (updated.size > MAX_RETAINED_MESSAGES) updated.takeLast(MAX_RETAINED_MESSAGES) else updated
    }

    // ---------------------------------------------------------------------------------------------
    // Awaiting helpers (flow based, bounded, no polling)
    // ---------------------------------------------------------------------------------------------

    /** Waits for the CURRENT session's peer to announce a known role. Null on timeout or disconnect. */
    suspend fun awaitPeerRole(timeoutMs: Long = 3000L): String? {
        val result = withTimeoutOrNull(timeoutMs) {
            combine(_connectionState, _remotePeerRole) { c, r ->
                if (c !is ConnectionState.Connected) "" else r
            }.first { it != null }
        }
        return result?.takeIf { it.isNotEmpty() }
    }

    // ---------------------------------------------------------------------------------------------
    // Sending
    // ---------------------------------------------------------------------------------------------

    private fun writeFrame(s: Session, message: SethuMessage): Boolean {
        val bytes = (message.toJsonString() + "\n").toByteArray(StandardCharsets.UTF_8)
        return try {
            synchronized(s.writeLock) {
                s.channel.output.write(bytes)
                s.channel.output.flush()
            }
            s.lastActivity = System.currentTimeMillis()
            true
        } catch (e: IOException) {
            failSession(s)
            false
        }
    }

    override fun sendMessage(payload: String, messageType: String): Boolean {
        val s = liveSession()
        if (s == null) {
            _transferStatus.value = TransferStatus.Failed("Cannot send: not connected to any device")
            return false
        }
        val message = SethuMessage(senderDeviceName = getLocalDeviceName(), messageType = messageType, payload = payload)
        _transferStatus.value = TransferStatus.Sending(message.messageId)
        return if (writeFrame(s, message)) {
            processedMessageIds.add(message.messageId)
            _sentMessages.value = capped(_sentMessages.value, message)
            _transferStatus.value = TransferStatus.MessageSent(message.messageId, message.payload.take(24))
            true
        } else {
            _transferStatus.value = TransferStatus.Failed("Socket transmission failed")
            false
        }
    }

    override fun sendSosReport(report: SosReport): Boolean {
        val s = liveSession()
        if (s == null) {
            _transferStatus.value = TransferStatus.Failed("Cannot send SOS: not connected to any device")
            return false
        }
        return sendSosReportOn(s, report)
    }

    /** Writes the SOS frame on exactly session [s], never on whatever connection happens to be current. */
    private fun sendSosReportOn(s: Session, report: SosReport): Boolean {
        // Preserve original sender name when forwarding, falling back to local name if unset
        val originalSender = report.senderDeviceName.takeIf { it.isNotBlank() && it != "Unknown" } ?: getLocalDeviceName()
        val message = SethuMessage(
            messageId = report.reportId,
            senderDeviceName = originalSender,
            timestamp = report.timestamp,
            messageType = SethuMessage.TYPE_SOS_REPORT,
            payload = report.toJsonString()
        )
        _transferStatus.value = TransferStatus.Sending(message.messageId)
        return if (writeFrame(s, message)) {
            processedMessageIds.add(message.messageId)
            _sentMessages.value = capped(_sentMessages.value, message)
            _transferStatus.value = TransferStatus.MessageSent(message.messageId, "SOS: ${report.category}")
            true
        } else {
            _transferStatus.value = TransferStatus.Failed("SOS socket write failed")
            false
        }
    }

    private fun sendControl(type: String, reportId: String): Boolean {
        val s = liveSession() ?: return false
        return sendControlOn(s, type, reportId)
    }

    private fun sendControlOn(s: Session, type: String, reportId: String): Boolean {
        if (s.closed.get()) return false
        return writeFrame(s, SethuMessage(senderDeviceName = getLocalDeviceName(), messageType = type, payload = reportId))
    }

    /** Relay acknowledgement of an SOS report ID; call only after durable persistence. */
    fun sendSosAck(reportId: String): Boolean = sendControl(SethuMessage.TYPE_SOS_ACK, reportId)

    /** Coordinator acknowledgement of an SOS report ID; call only after durable persistence. */
    fun sendCoordinatorAck(reportId: String): Boolean = sendControl(SethuMessage.TYPE_COORDINATOR_ACK, reportId)

    /** Sender's confirmation that it durably persisted the coordinator ACK for [reportId]. */
    fun sendCoordinatorAckReceipt(reportId: String): Boolean =
        sendControl(SethuMessage.TYPE_COORDINATOR_ACK_RECEIPT, reportId)

    /**
     * Announce our role on the current session. Idempotent per session unless [force] (used when the
     * local role changes mid-connection). Returns true if the role is announced on a live session.
     */
    fun sendRoleAnnouncement(roleName: String = localRoleName, force: Boolean = false): Boolean {
        val s = liveSession() ?: return false
        return announceRole(s, roleName, force)
    }

    private fun announceRole(s: Session, roleName: String = localRoleName, force: Boolean = false): Boolean {
        if (s.closed.get()) return false
        if (!force && !s.announced.compareAndSet(false, true)) return true
        val ok = writeFrame(
            s,
            SethuMessage(senderDeviceName = getLocalDeviceName(), messageType = SethuMessage.TYPE_ROLE_ANNOUNCE, payload = roleName)
        )
        if (!ok) s.announced.set(false)
        return ok
    }

    /**
     * Send an SOS report and await the matching ACK from the SAME session.
     * Returns true strictly if the matching report ID ACK is received before timeout/disconnect.
     */
    suspend fun sendSosReportAwaitingAck(
        report: SosReport,
        expectedAckType: String = SethuMessage.TYPE_SOS_ACK,
        timeoutMs: Long = 7000L,
        requiredSessionId: Long? = null
    ): Boolean {
        val s = liveSession() ?: return false
        // The caller validated the peer on a specific connection; refuse to send if that connection was replaced.
        if (requiredSessionId != null && s.id != requiredSessionId) return false
        val reportId = report.reportId
        if (!sendSosReportOn(s, report)) return false

        // Per-session, role-validated ACK sets: an ACK of another stage, from another peer, or from an earlier
        // connection can never satisfy this wait.
        val acks = if (expectedAckType == SethuMessage.TYPE_COORDINATOR_ACK) s.coordinatorAcks else s.sosAcks
        val outcome = withTimeoutOrNull(timeoutMs) {
            combine(acks, _connectionState) { ids, conn ->
                when {
                    ids.contains(reportId) -> 1
                    conn !is ConnectionState.Connected || session !== s || s.closed.get() -> -1
                    else -> 0
                }
            }.first { it != 0 }
        }
        return outcome == 1
    }

    // ---------------------------------------------------------------------------------------------
    // Teardown
    // ---------------------------------------------------------------------------------------------

    /**
     * Drops the current connection / in-flight connect attempt and returns to listening (if enabled).
     * This is what the delivery engine uses between peers; it never leaves the device deaf.
     */
    fun releaseConnection() {
        clientJob?.cancel()
        clientJob = null
        runBlockingSafeLock {
            abortOutboundLocked()
            closeSessionLocked(session)
            _remotePeerRole.value = null
            if (_connectionState.value !is ConnectionState.Failed) _connectionState.value = ConnectionState.NotConnected
        }
        ensureListening()
        serverWake.trySend(Unit)
    }

    /** User-initiated disconnect. Equivalent to [releaseConnection]; automatic listening stays enabled. */
    override fun disconnect() {
        clientJob?.cancel()
        clientJob = null
        runBlockingSafeLock {
            abortOutboundLocked()
            closeSessionLocked(session)
            _remotePeerRole.value = null
            _connectionState.value =
                if (listener != null && autoServerListening) ConnectionState.Listening("SethuEmergencyRFCOMM")
                else ConnectionState.NotConnected
        }
        ensureListening()
        serverWake.trySend(Unit)
    }

    /** Full teardown for process/Activity-owner end: stops listening, closes sockets, cancels jobs. */
    fun close() {
        autoServerListening = false
        serverJob?.cancel()
        serverJob = null
        clientJob?.cancel()
        clientJob = null
        runBlockingSafeLock {
            abortOutboundLocked()
            closeSessionLocked(session)
            closeListener()
            _connectionState.value = ConnectionState.NotConnected
            _serverState.value = ServerState.Stopped
        }
    }

    /**
     * Non-suspending state transition used by the non-suspend API. Transitions are tiny and never block on I/O,
     * so a try-lock spin with yield is bounded; closing sockets is itself non-blocking.
     */
    private inline fun runBlockingSafeLock(block: () -> Unit) {
        while (!connectionMutex.tryLock()) {
            Thread.yield()
        }
        try {
            block()
        } finally {
            connectionMutex.unlock()
        }
    }

    override fun clearTransferStatus() {
        _transferStatus.value = TransferStatus.Idle
    }
}
