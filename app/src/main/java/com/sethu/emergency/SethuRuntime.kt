package com.sethu.emergency

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import com.sethu.emergency.comm.bluetooth.BluetoothRfcommTransport
import com.sethu.emergency.comm.coordinator.CoordinatorReportStore
import com.sethu.emergency.comm.forwarding.StoreAndForwardController
import com.sethu.emergency.comm.relay.RelayReportStore
import com.sethu.emergency.comm.sender.SenderReportStore
import com.sethu.emergency.ui.screens.DeviceRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Owns the single Bluetooth transport, durable stores and delivery engine for the app session.
 *
 * It lives in a ViewModel, so Activity recreation (rotation, theme/locale change) and Compose
 * recomposition never create a second transport/server socket or duplicate controller; it is torn down
 * exactly once when the Activity is finished ([close] from onCleared). If Android kills the process, nothing
 * runs until the user reopens the app (no foreground service by design); queued reports stay on disk.
 */
class SethuRuntime(appContext: Context) {

    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val transport = BluetoothRfcommTransport(appContext, scope)
    val coordinatorStore = CoordinatorReportStore(appContext)
    val relayStore = RelayReportStore(appContext)
    val senderStore = SenderReportStore(appContext)
    val controller = StoreAndForwardController(
        context = appContext,
        transport = transport,
        senderStore = senderStore,
        relayStore = relayStore,
        coordinatorStore = coordinatorStore,
        scope = scope
    )

    init {
        controller.setLocalRole(loadRole())
        controller.start()
    }

    fun loadRole(): DeviceRole = try {
        DeviceRole.valueOf(prefs.getString(KEY_ROLE, null) ?: DeviceRole.SOS_SENDER.name)
    } catch (_: IllegalArgumentException) {
        DeviceRole.SOS_SENDER
    }

    fun setRole(role: DeviceRole) {
        prefs.edit().putString(KEY_ROLE, role.name).apply()
        controller.setLocalRole(role)
    }

    fun close() {
        controller.stop()
        transport.close()
        scope.cancel()
    }

    companion object {
        private const val PREFS = "sethu_runtime"
        private const val KEY_ROLE = "device_role"
    }
}

class SethuRuntimeViewModel(app: Application) : AndroidViewModel(app) {
    val runtime = SethuRuntime(app.applicationContext)

    override fun onCleared() {
        runtime.close()
    }
}
