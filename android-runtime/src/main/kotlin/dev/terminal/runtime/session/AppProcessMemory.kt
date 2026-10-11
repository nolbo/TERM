package dev.terminal.runtime.session

import android.os.Debug
import org.json.JSONObject

/** Each app process measures itself; no process identifiers or private paths cross IPC. */
fun currentAppProcessMemory(): JSONObject? = runCatching {
    val info = Debug.MemoryInfo()
    Debug.getMemoryInfo(info)
    check(info.totalPss > 0) { "Process PSS is unavailable" }
    JSONObject().put("basis", "pss").put("total", info.totalPss.toLong() * 1024).apply {
        for (key in listOf("java-heap", "native-heap", "code", "stack", "graphics", "private-other", "system")) {
            val kib = info.getMemoryStat("summary.$key")?.toLongOrNull()?.takeIf { it >= 0 }
            put(key, kib?.times(1024) ?: JSONObject.NULL)
        }
    }
}.getOrNull()
