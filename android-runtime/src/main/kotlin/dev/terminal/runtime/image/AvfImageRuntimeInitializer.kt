package dev.terminal.runtime.image

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import dev.terminal.runtime.ipc.IAvfImageInitializationCallback
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.lifecycle.VmRuntimeService
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/** The installer observes service-owned VM work; its cancellation targets this attempt only. */
internal suspend fun initializeAvfImage(context: Context, storageId: String,
    onProgress: (AvfImageInstallPhase) -> Unit) {
    val requestId = UUID.randomUUID().toString()
    val connected = CompletableDeferred<IRuntimeService>()
    val finished = CompletableDeferred<String>()
    var remote: IRuntimeService? = null
    val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            connected.complete(IRuntimeService.Stub.asInterface(binder))
        }
        override fun onServiceDisconnected(name: ComponentName) {
            val error = IOException("AVF 초기화 서비스 연결이 끊겼습니다. 패키지 목록 갱신을 다시 요청하세요.")
            connected.completeExceptionally(error)
            finished.completeExceptionally(error)
        }
        override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
        override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
    }
    check(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE)) {
        "AVF 초기화 서비스에 연결할 수 없습니다."
    }
    try {
        withTimeout(450_000) {
            remote = connected.await()
            remote!!.initializeAvfImage(storageId, requestId, object : IAvfImageInitializationCallback.Stub() {
                override fun onProgress(phase: String) {
                    val parsed = runCatching { AvfImageInstallPhase.valueOf(phase) }.getOrNull()
                    if (parsed == AvfImageInstallPhase.BOOT || parsed == AvfImageInstallPhase.APT_REFRESH)
                        runCatching { onProgress(parsed) }.onFailure { finished.completeExceptionally(it) }
                }
                override fun onComplete(error: String) { finished.complete(error) }
            })
            val error = finished.await()
            if (error.isNotEmpty()) throw IllegalStateException(error)
        }
    } finally {
        runCatching { remote?.cancelAvfImageInitialization(requestId) }
        context.unbindService(connection)
    }
}
