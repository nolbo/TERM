package dev.terminal.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.terminal.app.ui.TerminalActivity
import dev.terminal.app.ui.UsbSettingsAction
import dev.terminal.app.ui.UsbSettingsCommand
import dev.terminal.app.ui.UsbSettingsScreen
import dev.terminal.app.ui.UsbSettingsSnapshot
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.lifecycle.VmRuntimeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Only projects service state; navigating away leaves approved USB work running. */
class UsbDevicesActivity : TerminalActivity() {
    private var runtime by mutableStateOf<IRuntimeService?>(null)
    private var state by mutableStateOf<UsbSettingsSnapshot?>(null)
    private var error by mutableStateOf("")
    private var command by mutableStateOf<UsbSettingsCommand?>(null)
    private var storageSelection by mutableStateOf<Pair<String, Int>?>(null)
    private var binding = false
    private val pickStorage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) result.data?.let { data ->
            data.data?.let { storageSelection = it.toString() to data.flags }
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) { runtime = IRuntimeService.Stub.asInterface(binder) }
        override fun onServiceDisconnected(name: ComponentName) { runtime = null; state = null; error = getString(R.string.usb_service_unavailable) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        compose {
            LaunchedEffect(storageSelection, runtime) {
                val selection = storageSelection ?: return@LaunchedEffect
                val service = runtime ?: return@LaunchedEffect
                runCatching { withContext(Dispatchers.IO) { service.addUsbStorageTree(selection.first, selection.second) } }
                    .onSuccess { error = if (it.isEmpty()) "" else actionError(it) }
                    .onFailure { error = getString(R.string.usb_action_failed) }
                storageSelection = null
            }
            LaunchedEffect(runtime) {
                val service = runtime ?: return@LaunchedEffect
                while (true) {
                    runCatching { withContext(Dispatchers.IO) { UsbSettingsSnapshot.parse(JSONObject(service.usbStateJson)) } }
                        .onSuccess { state = it }.onFailure { state = null; error = getString(R.string.usb_service_unavailable) }
                    delay(1000)
                }
            }
            LaunchedEffect(command) {
                val action = command ?: return@LaunchedEffect
                val service = runtime
                if (service == null) error = getString(R.string.usb_service_unavailable)
                else runCatching {
                    withContext(Dispatchers.IO) {
                        when (action.action) {
                            UsbSettingsAction.APPROVE -> service.approveUsbRequest(action.id)
                            UsbSettingsAction.DENY -> { service.denyUsbRequest(action.id); "" }
                            UsbSettingsAction.REMOVE_STORAGE -> { service.removeUsbStorageTree(action.id); "" }
                            UsbSettingsAction.REVOKE -> { service.revokeUsbSession(action.id); "" }
                        }
                    }
                }.onSuccess { error = if (it.isEmpty()) "" else actionError(it) }
                    .onFailure { error = getString(R.string.usb_action_failed) }
                command = null
            }
            UsbSettingsScreen(state, runtime != null, command != null || storageSelection != null, error,
                onBack = { finish() }, onCommand = { command = it }, onDismissError = { error = "" },
                onHostInputs = { startActivity(Intent(this@UsbDevicesActivity, HostToolInputsActivity::class.java)) },
                onStorage = {
                    runCatching { pickStorage.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)) }
                        .onFailure { error = getString(R.string.usb_storage_picker_unavailable) }
                })
        }
    }

    private fun actionError(code: String): String = getString(when (code.substringBefore(':')) {
        "REQUEST_EXPIRED" -> R.string.usb_request_expired
        "DEVICE_DISCONNECTED" -> R.string.usb_device_detached
        "STORAGE_UNAVAILABLE", "STORAGE_READ_ONLY", "INVALID_STORAGE_TREE" -> R.string.usb_storage_access_failed
        "DEVICE_BUSY", "INTERFACE_BUSY" -> R.string.usb_device_busy
        "USB_PERMISSION_DENIED", "ANDROID_PERMISSION_REQUIRED" -> R.string.usb_permission_error
        else -> R.string.usb_action_failed
    })

    override fun onStart() {
        super.onStart()
        binding = bindService(Intent(this, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE)
        if (!binding) error = getString(R.string.usb_service_unavailable)
    }
    override fun onStop() {
        runtime = null; state = null
        if (binding) unbindService(connection)
        binding = false
        super.onStop()
    }
}
