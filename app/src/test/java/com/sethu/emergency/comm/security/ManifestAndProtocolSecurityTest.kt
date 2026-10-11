package com.sethu.emergency.comm.security

import com.sethu.emergency.comm.bluetooth.BluetoothRfcommTransport
import com.sethu.emergency.ui.screens.DeviceRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Static guards for the strict offline-only / least-exposure requirements (source manifest + wire constants). */
class ManifestAndProtocolSecurityTest {

    private fun manifestFile(): File =
        listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").map(::File).first { it.exists() }

    private val doc by lazy { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifestFile()) }

    private fun permissions(): List<String> {
        val n = doc.getElementsByTagName("uses-permission")
        return (0 until n.length).map { n.item(it).attributes.getNamedItem("android:name").nodeValue }
    }

    @Test fun noInternetOrNetworkStatePermissionIsDeclared() {
        val p = permissions()
        assertFalse(p.any { it.contains("INTERNET") })
        assertFalse(p.any { it.contains("ACCESS_NETWORK_STATE") || it.contains("ACCESS_WIFI_STATE") || it.contains("CHANGE_NETWORK_STATE") })
    }

    @Test fun onlyTheLauncherActivityIsDeclaredAndExported_noServicesReceiversOrProviders() {
        assertEquals(1, doc.getElementsByTagName("activity").length)
        assertEquals(0, doc.getElementsByTagName("service").length)
        assertEquals(0, doc.getElementsByTagName("receiver").length)
        assertEquals(0, doc.getElementsByTagName("provider").length)
        val act = doc.getElementsByTagName("activity").item(0)
        assertEquals("true", act.attributes.getNamedItem("android:exported").nodeValue)
    }

    @Test fun cloudBackupOfEmergencyDataIsDisabled() {
        val app = doc.getElementsByTagName("application").item(0)
        assertEquals("false", app.attributes.getNamedItem("android:allowBackup").nodeValue)
    }

    @Test fun bluetoothPermissionsDeclaredForBothAndroidGenerations() {
        val p = permissions()
        assertTrue(p.contains("android.permission.BLUETOOTH_CONNECT"))
        assertTrue(p.contains("android.permission.BLUETOOTH_SCAN"))
        assertTrue(p.contains("android.permission.BLUETOOTH"))
        assertTrue(p.contains("android.permission.BLUETOOTH_ADMIN"))
    }

    @Test fun wireRoleNamesMatchDeviceRoleEnum() {
        assertEquals(DeviceRole.values().map { it.name }.toSet(), BluetoothRfcommTransport.KNOWN_ROLES)
    }
}
