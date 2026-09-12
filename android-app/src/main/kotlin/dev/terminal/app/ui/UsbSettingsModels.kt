package dev.terminal.app.ui

import org.json.JSONArray
import org.json.JSONObject

/** A display-only, allowlisted projection. No diagnostic JSON is rendered verbatim. */
internal data class UsbSettingsSnapshot(
    val supported: Boolean,
    val devices: List<UsbDeviceUi>,
    val requests: List<UsbAccessUi>,
    val grants: List<UsbAccessUi>,
    val storage: List<UsbStorageUi>,
    val diagnostics: List<Pair<String, Int>>,
) {
    companion object {
        fun parse(json: JSONObject) = UsbSettingsSnapshot(
            json.optBoolean("supported"),
            json.optJSONArray("devices").objects().map { device ->
                val interfaces = device.optJSONArray("configurations").objects().flatMap { config ->
                    config.optJSONArray("interfaces").objects().map { intf ->
                        UsbInterfaceUi(config.optInt("value"), intf.optInt("id"), intf.optInt("alternate"),
                            intf.optInt("class"), intf.optInt("subclass"), intf.optInt("protocol"))
                    }
                }
                UsbDeviceUi(device.optString("id"), device.optInt("vendor"), device.optInt("product"),
                    device.optBoolean("iphone"), device.optBoolean("cdcAcm"),
                    device.optJSONArray("serialPorts").objects().map { it.optString("driver") }.filter { it.isNotEmpty() }.distinct(), interfaces)
            },
            json.optJSONArray("requests").objects().map(UsbAccessUi::parse),
            json.optJSONArray("grants").objects().map(UsbAccessUi::parse),
            json.optJSONArray("storageRoots").objects().map {
                UsbStorageUi(it.optString("id"), it.optString("name"), it.optBoolean("available"), it.optBoolean("writable"))
            },
            listOf("rawConnections", "serialConnections", "iphoneOperations", "fileConnections", "pendingApprovals")
                .map { it to (json.optJSONObject("diagnostics")?.optInt(it) ?: 0).coerceAtLeast(0) },
        )
    }
}

internal data class UsbDeviceUi(
    val id: String, val vendor: Int, val product: Int, val iphone: Boolean,
    val cdc: Boolean, val serialDrivers: List<String>, val interfaces: List<UsbInterfaceUi>,
) {
    val hardwareId: String get() = "%04x:%04x".format(vendor, product)
}
internal data class UsbInterfaceUi(val configuration: Int, val id: Int, val alternate: Int,
    val deviceClass: Int, val subclass: Int, val protocol: Int)
internal data class UsbStorageUi(val id: String, val name: String, val available: Boolean, val writable: Boolean)
internal data class UsbAccessUi(
    val requestId: String, val session: String, val runtime: String, val device: String,
    val vendor: Int, val product: Int, val interfaceId: Int, val serial: Boolean,
    val driver: String, val port: Int, val configuration: Int, val alternate: Int,
    val iphone: String, val tool: String, val scope: String, val waiting: Boolean, val active: Boolean,
) {
    companion object {
        fun parse(value: JSONObject) = UsbAccessUi(value.optString("request"), value.optString("session"),
            value.optString("runtime"), value.optString("device"), value.optInt("vendor"), value.optInt("product"),
            value.optInt("interface"), value.optBoolean("serial"), value.optString("driver"), value.optInt("port"),
            value.optInt("configuration"), value.optInt("alternate"), value.optString("iphone"), value.optString("tool"),
            value.optString("scope"), value.optBoolean("waitingForAndroid"), value.optBoolean("active"))
    }
}

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList()
    else (0 until length()).mapNotNull { optJSONObject(it) }
