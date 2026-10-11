package com.sethu.emergency.comm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object BluetoothPermissionHelper {

    /**
     * Runtime permissions needed for RFCOMM to bonded devices.
     * - Android 12+ (API 31+): BLUETOOTH_CONNECT (listen/connect/bonded list) and BLUETOOTH_SCAN
     *   (BluetoothAdapter.cancelDiscovery) are runtime permissions.
     * - Android 11 and below: BLUETOOTH and BLUETOOTH_ADMIN are install-time ("normal") permissions declared in
     *   the manifest. Location is only needed for discovery scans, which Sethu never performs, so it is NOT
     *   required here (requiring it would block the engine on e.g. the Vivo Y15 until location is granted).
     */
    fun getRequiredPermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            emptyList()
        }
    }

    fun hasPermissions(context: Context): Boolean {
        return getRequiredPermissions().all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
    }
}
