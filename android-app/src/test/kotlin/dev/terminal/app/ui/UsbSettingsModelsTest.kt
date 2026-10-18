package dev.terminal.app.ui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UsbSettingsModelsTest {
    @Test fun absentAndUnknownFieldsDoNotBecomeDisplayData() {
        val empty = UsbSettingsSnapshot.parse(JSONObject())
        assertFalse(empty.supported)
        assertTrue(empty.devices.isEmpty())
        assertTrue(empty.requests.isEmpty())
        val data = UsbSettingsSnapshot.parse(JSONObject("""{
            "supported": true, "password": "secret-sentinel",
            "diagnostics": {"rawConnections":2,"recentErrors":["secret-sentinel"],"token":"secret-sentinel"}
        }"""))
        assertTrue(data.supported)
        assertEquals(2, data.diagnostics.first().second)
        assertFalse(data.toString().contains("secret-sentinel"))
    }

    @Test fun deviceConfigurationsAndSerialDriversAreProjectedWithoutInventingPermission() {
        val data = UsbSettingsSnapshot.parse(JSONObject("""{
            "supported":true,"devices":[{"id":"phone","vendor":1452,"product":4776,"iphone":true,
              "serialPorts":[{"driver":"ftdi","port":0},{"driver":"ftdi","port":1}],
              "configurations":[{"value":3,"interfaces":[{"id":1,"alternate":2,"class":255,"subclass":254,"protocol":2}]}]}]
        }"""))
        val device = data.devices.single()
        assertTrue(device.iphone)
        assertEquals("05ac:12a8", device.hardwareId)
        assertEquals(listOf("ftdi"), device.serialDrivers)
        assertEquals(UsbInterfaceUi(3,1,2,255,254,2), device.interfaces.single())
    }

    @Test fun interactiveStatesAndScopeStaySeparateFromUsbPermission() {
        val data = UsbSettingsSnapshot.parse(JSONObject("""{
            "requests":[{"request":"request-id","session":"session","runtime":"ROOTLESS","device":"phone",
                "interface":-1,"tool":"idevice-check","waitingForAndroid":true,"scope":"/allowed"}],
            "grants":[{"session":"session","device":"phone","iphone":"afc-get","active":true,"scope":"/file"}],
            "storageRoots":[{"id":"root","name":"Drive","available":true,"writable":false}],
            "diagnostics":{"rawConnections":-1}
        }"""))
        assertTrue(data.requests.single().waiting)
        assertEquals("request-id", data.requests.single().requestId)
        assertEquals(-1, data.requests.single().interfaceId)
        assertEquals("idevice-check", data.requests.single().tool)
        assertTrue(data.grants.single().active)
        assertEquals("/file", data.grants.single().scope)
        assertEquals(UsbStorageUi("root","Drive",true,false), data.storage.single())
        assertEquals(0, data.diagnostics.first().second)
    }
}
