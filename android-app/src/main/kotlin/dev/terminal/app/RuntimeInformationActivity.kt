package dev.terminal.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import dev.terminal.app.ui.TerminalActivity
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.lifecycle.VmRuntimeService

/** Read-only screens bind while visible; leaving never stops a runtime or session. */
abstract class RuntimeInformationActivity : TerminalActivity() {
    protected var runtime: IRuntimeService? = null
        private set
    private var bindingRequested = false
    private var activityStarted = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (!activityStarted) {
                if (bindingRequested) runCatching { unbindService(this) }
                bindingRequested = false
                return
            }
            runtime = IRuntimeService.Stub.asInterface(service)
            runtimeInformationChanged()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            runtime = null
            runtimeInformationChanged()
        }
    }

    override fun onStart() {
        super.onStart()
        if (isFinishing) return
        activityStarted = true
        bindingRequested = runCatching {
            bindService(Intent(this, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
    }

    override fun onStop() {
        activityStarted = false
        if (bindingRequested) runCatching { unbindService(connection) }
        bindingRequested = false
        runtime = null
        super.onStop()
    }

    protected abstract fun runtimeInformationChanged()
}
