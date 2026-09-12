package dev.terminal.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.terminal.app.ui.AppScaffold
import dev.terminal.app.ui.TerminalActivity
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.lifecycle.VmRuntimeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Only projects service state; navigating away leaves approved USB work running. */
class UsbDevicesActivity : TerminalActivity() {
    private var runtime by mutableStateOf<IRuntimeService?>(null)
    private var state by mutableStateOf<JSONObject?>(null)
    private var error by mutableStateOf("")
    private var confirmation by mutableStateOf<JSONObject?>(null)
    private var command by mutableStateOf<Pair<String, String>?>(null)
    private var storageSelection by mutableStateOf<Pair<String, Int>?>(null)
    private var binding = false
    private val pickStorage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) result.data?.let { data ->
            data.data?.let { storageSelection = it.toString() to data.flags }
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            runtime = IRuntimeService.Stub.asInterface(binder)
        }
        override fun onServiceDisconnected(name: ComponentName) { runtime = null; state = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        compose {
            LaunchedEffect(storageSelection, runtime) {
                val selection = storageSelection ?: return@LaunchedEffect
                val service = runtime ?: return@LaunchedEffect
                runCatching { withContext(Dispatchers.IO) { service.addUsbStorageTree(selection.first, selection.second) } }
                    .onSuccess { error = it }.onFailure { error = it.message.orEmpty() }
                storageSelection = null
            }
            LaunchedEffect(runtime) {
                val service = runtime ?: return@LaunchedEffect
                while (true) {
                    runCatching { withContext(Dispatchers.IO) { JSONObject(service.usbStateJson) } }
                        .onSuccess { state = it }.onFailure { error = it.message.orEmpty() }
                    delay(1000)
                }
            }
            LaunchedEffect(command) {
                val action = command ?: return@LaunchedEffect
                val service = runtime
                if (service != null) runCatching {
                    withContext(Dispatchers.IO) {
                        when (action.first) {
                            "approve" -> service.approveUsbRequest(action.second)
                            "deny" -> { service.denyUsbRequest(action.second); "" }
                            "remove-storage" -> { service.removeUsbStorageTree(action.second); "" }
                            else -> { service.revokeUsbSession(action.second); "" }
                        }
                    }
                }.onSuccess { error = it }.onFailure { error = it.message.orEmpty() }
                command = null
            }
            AppScaffold(stringResource(R.string.usb_title), { finish() }) { padding ->
                LazyColumn(Modifier.padding(padding).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text(stringResource(R.string.usb_help)) }
                    if (state == null) item { Text(stringResource(R.string.usb_connecting)) }
                    if (state?.optBoolean("supported") == false) item { Text(stringResource(R.string.usb_unsupported)) }
                    if (error.isNotEmpty()) item { Text(error) }
                    item {
                        Button(onClick = {
                            runCatching { pickStorage.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)) }
                                .onFailure { error = getString(R.string.usb_storage_picker_unavailable) }
                        }, enabled = runtime != null) { Text(stringResource(R.string.usb_add_storage)) }
                        Text(stringResource(R.string.usb_storage_help))
                    }
                    val roots = state?.optJSONArray("storageRoots")
                    repeat(roots?.length() ?: 0) { index ->
                        val root = roots!!.getJSONObject(index)
                        item(key = "storage-${root.getString("id")}") {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(root.getString("name"))
                                    Text(root.getString("id"))
                                    Text(stringResource(if (!root.optBoolean("available")) R.string.usb_storage_unavailable
                                        else if (root.optBoolean("writable")) R.string.usb_storage_writable else R.string.usb_storage_readonly))
                                    TextButton(onClick = { command = "remove-storage" to root.getString("id") }) {
                                        Text(stringResource(R.string.usb_remove_storage))
                                    }
                                }
                            }
                        }
                    }
                    val requests = state?.optJSONArray("requests")
                    if (requests?.length() == 0) item { Text(stringResource(R.string.usb_no_requests)) }
                    repeat(requests?.length() ?: 0) { index ->
                        val request = requests!!.getJSONObject(index)
                        item(key = request.getString("request")) {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(requestLabel(request))
                                    if (request.getBoolean("waitingForAndroid")) Text(stringResource(R.string.usb_waiting_permission))
                                    Row {
                                        Button(onClick = { confirmation = request }, enabled = command == null &&
                                            !request.getBoolean("waitingForAndroid")) { Text(stringResource(R.string.usb_approve)) }
                                        TextButton(onClick = { command = "deny" to request.getString("request") }) {
                                            Text(stringResource(R.string.usb_deny))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    val grants = state?.optJSONArray("grants")
                    repeat(grants?.length() ?: 0) { index ->
                        val grant = grants!!.getJSONObject(index)
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(requestLabel(grant))
                                    Text(stringResource(if (grant.optBoolean("active")) R.string.usb_connection_active else R.string.usb_grant_idle))
                                    TextButton(onClick = { command = "revoke" to grant.getString("session") }) {
                                        Text(stringResource(R.string.usb_revoke))
                                    }
                                }
                            }
                        }
                    }
                    val devices = state?.optJSONArray("devices")
                    if (state?.optBoolean("supported") == true && devices?.length() == 0)
                        item { Text(stringResource(R.string.usb_no_devices)) }
                    repeat(devices?.length() ?: 0) { index ->
                        val device = devices!!.getJSONObject(index)
                        item(key = device.getString("id")) {
                            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                                Text("%04x:%04x".format(device.getInt("vendor"), device.getInt("product")))
                                Text(device.getString("id"))
                                val ports = device.optJSONArray("serialPorts")
                                if ((ports?.length() ?: 0) > 0) Text("Serial: $ports")
                                val configurations = device.optJSONArray("configurations")
                                repeat(configurations?.length() ?: 0) { c ->
                                    val config = configurations!!.getJSONObject(c)
                                    val interfaces = config.getJSONArray("interfaces")
                                    repeat(interfaces.length()) { i ->
                                        val intf = interfaces.getJSONObject(i)
                                        val protocol = when {
                                            intf.getInt("class") == 255 && intf.getInt("subclass") == 66 && intf.getInt("protocol") == 1 -> "ADB"
                                            intf.getInt("class") == 6 && intf.getInt("subclass") == 1 && intf.getInt("protocol") == 1 -> "MTP/PTP"
                                            intf.getInt("class") == 3 -> "HID"
                                            intf.getInt("class") == 8 -> "Storage: Android folder picker"
                                            else -> "Raw (device-specific protocol required)"
                                        }
                                        Text("Config ${config.getInt("value")} · interface ${intf.getInt("id")} · alt ${intf.getInt("alternate")} · $protocol")
                                    }
                                }
                            } }
                        }
                    }
                    state?.optJSONObject("diagnostics")?.let { diagnostics ->
                        item { Text(stringResource(R.string.usb_diagnostics)); Text(diagnostics.toString(2)) }
                    }
                }
            }
            confirmation?.let { request ->
                AlertDialog(onDismissRequest = { confirmation = null },
                    title = { Text(stringResource(R.string.usb_approve)) },
                    text = { Text(requestLabel(request) + "\n\n" + getString(
                        if (request.optString("driver") == "configuration") R.string.usb_configuration_confirmation
                        else if (request.optString("driver").startsWith("storage-")) R.string.usb_storage_confirmation
                        else if (request.optString("iphone").isNotEmpty()) R.string.usb_iphone_confirmation
                        else R.string.usb_confirmation)) },
                    confirmButton = { TextButton(onClick = {
                        command = "approve" to request.getString("request"); confirmation = null
                    }) { Text(stringResource(R.string.usb_approve)) } },
                    dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.usb_deny)) } })
            }
        }
    }

    private fun requestLabel(value: JSONObject): String {
        if (value.optString("driver").startsWith("storage-")) return "${value.getString("runtime")} · ${value.getString("session")}\n" +
                getString(if (value.optString("driver") == "storage-write") R.string.usb_storage_write_grant else R.string.usb_storage_read_grant) +
                "\n${value.optString("scope")}"
        return getString(R.string.usb_request_label,
        value.getString("runtime"), value.getString("session"),
        "%04x:%04x".format(value.getInt("vendor"), value.getInt("product")), value.getInt("interface"),
        getString(when (value.optString("iphone")) {
            "pair" -> R.string.usb_iphone_pair
            "info" -> R.string.usb_iphone_info
            "unpair" -> R.string.usb_iphone_unpair
            "afc-list" -> R.string.usb_iphone_list
            "afc-get" -> R.string.usb_iphone_get
            "afc-put" -> R.string.usb_iphone_put
            else -> if (value.optString("driver") == "configuration") R.string.usb_configuration_grant
                else if (value.optString("driver").isNotEmpty()) R.string.usb_serial_driver
                else if (value.getBoolean("serial")) R.string.usb_cdc else R.string.usb_raw
        })) + value.optString("scope").let { if (it.isEmpty()) "" else "\nScope: $it" } +
        value.optString("driver").let { if (it.isEmpty()) "" else "\n$it" + if (it !in setOf("configuration", "storage-read", "storage-write")) " · port ${value.optInt("port")}" else "" } +
        if (value.optInt("configuration") > 0) "\nConfig ${value.optInt("configuration")} · alt ${value.optInt("alternate")}" else ""
    }

    override fun onStart() {
        super.onStart()
        binding = bindService(Intent(this, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        runtime = null; state = null; confirmation = null
        if (binding) unbindService(connection)
        binding = false
        super.onStop()
    }
}
