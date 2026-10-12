package dev.terminal.bridge

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.util.concurrent.ConcurrentHashMap

/** Opaque, host-owned SAF handles for the future guest androidfs FUSE daemon. */
class SafStorageBroker(context: Context) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val handles = ConcurrentHashMap<String, ParcelFileDescriptor>()

    fun open(handle: String, uri: Uri, mode: String): BridgeResult<Unit> = runCatching {
        require(HANDLE.matches(handle)) { "Invalid storage handle" }
        require(mode in setOf("r", "rw")) { "Unsupported storage mode" }
        val descriptor = resolver.openFileDescriptor(uri, mode) ?: error("Provider did not return a file descriptor")
        handles.put(handle, descriptor)?.close()
        BridgeResult.Success(Unit)
    }.getOrElse { BridgeResult.Invalid(it.message ?: "Cannot open SAF document") }

    fun close(handle: String) { handles.remove(handle)?.close() }
    fun closeAll() { handles.keys.forEach(::close) }

    private companion object { val HANDLE = Regex("[A-Za-z0-9._-]{1,96}") }
}
