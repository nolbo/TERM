package dev.terminal.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.terminal.app.ui.SettingsPageScaffold
import dev.terminal.app.ui.VisiblePolling
import dev.terminal.app.ui.TerminalActivity
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.lifecycle.VmRuntimeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Generic tool input projection. No secret saved state and no process/UI lifetime coupling. */
class HostToolInputsActivity : TerminalActivity() {
    private var service by mutableStateOf<IRuntimeService?>(null)
    private var operations by mutableStateOf(JSONArray())
    private var account by mutableStateOf("")
    private var password by mutableStateOf("")
    private var code by mutableStateOf("")
    private var endpoint by mutableStateOf("https://ani.sidestore.io")
    private var message by mutableStateOf("")
    private var binding = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) { service = IRuntimeService.Stub.asInterface(binder) }
        override fun onServiceDisconnected(name: ComponentName) { service = null; operations = JSONArray(); clearSecrets() }
    }
    private fun clearSecrets() { account = ""; password = ""; code = "" }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        compose {
            val scope = rememberCoroutineScope()
            var busy by remember { mutableStateOf(false) }
            VisiblePolling(service, busy) {
                val runtime = service ?: return@VisiblePolling
                while (true) {
                    runCatching { withContext(Dispatchers.IO) { JSONArray(runtime.hostToolOperationsJson) } }
                        .onSuccess { operations = it }.onFailure { message = "HOST_INPUT_UNAVAILABLE" }
                    val active = (0 until operations.length()).any { operations.getJSONObject(it).optString("state") == "RUNNING" }
                    delay(if (active) 1_000 else 10_000)
                }
            }
            SettingsPageScaffold(stringResource(R.string.host_tool_inputs_title), { finish() }) { padding ->
                LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text(stringResource(R.string.host_tool_inputs_help)) }
                    if (message.isNotEmpty()) item { Text(message) }
                    if (operations.length() == 0) item { Text(stringResource(R.string.host_tool_none)) }
                    repeat(operations.length()) { index ->
                        val operation = operations.getJSONObject(index)
                        item(key = operation.getString("operation")) {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(operation.getString("tool"), style = MaterialTheme.typography.titleMedium)
                                    Text(operation.getString("operation"))
                                    Text(operation.optString("session"))
                                    Text(operation.getString("phase") + " · " + operation.getString("state"))
                                    operation.optJSONObject("information")?.let { Text("${it.optString("name")} · ${it.optString("model")} · iOS ${it.optString("ios")}") }
                                    if (operation.has("progress")) LinearProgressIndicator(progress = { operation.optDouble("progress").toFloat() }, modifier = Modifier.fillMaxWidth())
                                    if (operation.has("error")) Text(getString(errorResource(operation.optString("error"))) + "\n" + operation.optString("error") +
                                        operation.optString("detail").let { if (it.isEmpty()) "" else " · $it" })
                                    operation.optJSONObject("changes")?.let { changes ->
                                        Text(stringResource(R.string.host_tool_changes_notice))
                                        for ((key, label) in listOf("devicePairing" to R.string.host_tool_change_pair,
                                            "developerAccount" to R.string.host_tool_change_account, "appInstallation" to R.string.host_tool_change_install,
                                            "wifiDebugging" to R.string.host_tool_change_wifi, "pairingFile" to R.string.host_tool_change_file)) {
                                            if (changes.has(key)) Text(getString(label) + ": " + getString(when (changes.optString(key)) {
                                                "CONFIRMED" -> R.string.host_tool_change_confirmed
                                                "UNCHANGED" -> R.string.host_tool_change_unchanged
                                                else -> R.string.host_tool_change_possible
                                            }))
                                        }
                                    }
                                    if (operation.optString("state") == "COMPLETED") Text(stringResource(R.string.host_tool_completed))
                                    val prompt = operation.optJSONObject("prompt")
                                    if (prompt != null) Input(prompt) { values ->
                                        val runtime = service
                                        if (runtime == null || busy) { values.fill(""); return@Input }
                                        busy = true; clearSecrets()
                                        scope.launch {
                                            try { message = withContext(Dispatchers.IO) { runtime.submitHostToolInput(operation.getString("operation"), prompt.getString("nonce"), values) } }
                                            catch (_: Exception) { message = "HOST_INPUT_UNAVAILABLE" }
                                            finally { values.fill(""); busy = false }
                                        }
                                    }
                                    if (operation.optString("state") == "RUNNING") TextButton(enabled = !busy && service != null, onClick = {
                                        clearSecrets(); scope.launch { runCatching { withContext(Dispatchers.IO) { service?.cancelHostToolOperation(operation.getString("operation")) } } }
                                    }) { Text(stringResource(R.string.host_tool_cancel)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    @Composable private fun Input(prompt: JSONObject, submit: (Array<String>) -> Unit) {
        val nonce = prompt.getString("nonce"); val kind = prompt.getString("kind")
        var advanced by remember(nonce) { mutableStateOf(false) }
        var selected by remember(nonce) { mutableStateOf(emptySet<String>()) }
        LaunchedEffect(nonce) { clearSecrets() }
        if (kind == "LOGIN") {
            OutlinedTextField(account, { account = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.host_tool_account)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Email))
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.host_tool_password)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password))
            TextButton(onClick = { advanced = !advanced }) { Text(stringResource(R.string.host_tool_advanced)) }
            if (advanced) { OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.host_tool_endpoint)) }, singleLine = true); Text(stringResource(R.string.host_tool_endpoint_help)) }
        } else if (kind == "CODE") {
            OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.host_tool_code)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.NumberPassword))
        } else {
            Text(stringResource(if (kind == "CERTIFICATES") R.string.host_tool_revoke_warning else R.string.host_tool_team))
            val choices = prompt.getJSONArray("choices")
            repeat(choices.length()) { index ->
                val choice = choices.getJSONObject(index); val id = choice.getString("id")
                Row { Checkbox(id in selected, { checked -> selected = if (kind == "TEAM") setOf(id) else if (checked) selected + id else selected - id }); Text(choice.getString("label") + "\n" + id) }
            }
        }
        Button(onClick = { submit(when (kind) { "LOGIN" -> arrayOf(account, password, endpoint); "CODE" -> arrayOf(code); else -> selected.toTypedArray() }) }) {
            Text(stringResource(R.string.host_tool_submit))
        }
    }
    private fun errorResource(code: String): Int = when (code) {
        "USB_CONFIGURATION_REQUIRED" -> R.string.host_tool_error_configuration
        "PAIR_RECORD_READ_FAILED", "PAIR_RECORD_SAVE_FAILED" -> R.string.host_tool_error_pair_store
        "PAIR_RECORD_INVALID", "PAIR_RECORD_REJECTED" -> R.string.host_tool_error_pair_record
        "PAIRING_TRANSPORT", "PAIRING_TIMEOUT" -> R.string.host_tool_error_pair_transport
        "PAIRING_RESPONSE", "PAIR_SESSION_FAILED" -> R.string.host_tool_error_pair_response
        "DEVICE_LOCKED", "TRUST_DENIED", "PAIRING_FAILED", "USB_DETACHED", "USB_PERMISSION_DENIED" -> R.string.host_tool_error_device
        "NETWORK", "ANISETTE", "DOWNLOAD" -> R.string.host_tool_error_network
        "APPLE_CREDENTIALS", "TWO_FACTOR", "TWO_FACTOR_TIMEOUT", "INPUT_TIMEOUT" -> R.string.host_tool_error_login
        "CERTIFICATE_LIMIT", "APP_ID_LIMIT", "NO_DEVELOPER_TEAM", "PROVISIONING", "IOS_CERTIFICATE_REJECTED" -> R.string.host_tool_error_developer
        else -> R.string.host_tool_error_generic
    }
    override fun onStart() { super.onStart(); binding = bindService(Intent(this, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE) }
    override fun onStop() { clearSecrets(); if (binding) unbindService(connection); binding = false; service = null; super.onStop() }
}
