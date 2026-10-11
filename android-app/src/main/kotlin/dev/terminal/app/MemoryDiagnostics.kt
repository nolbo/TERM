package dev.terminal.app

import android.app.ActivityManager
import android.content.Context
import dev.terminal.runtime.session.currentAppProcessMemory
import org.json.JSONObject

internal val appMemoryKeys = listOf("total", "java-heap", "native-heap", "code", "stack", "graphics", "private-other", "system")
internal fun JSONObject.memoryBytes(key: String): Long? = if (!has(key) || isNull(key)) null
    else (opt(key) as? Number)?.toLong()?.takeIf { it >= 0 }

/** Unavailable or inconsistent measurements never become a zero or a full usage bar. */
internal fun memoryRatio(bytes: Long?, total: Long?): Float? {
    if (bytes == null || total == null || total <= 0 || bytes < 0 || bytes > total) return null
    return (bytes.toDouble() / total).toFloat()
}

internal data class MemoryCompositionSegments(val fractions: List<Double>, val difference: Long)

/** Keeps the measured PSS denominator; never normalizes summary values into a new total. */
internal fun memoryCompositionSegments(values: List<Long?>, total: Long?): MemoryCompositionSegments? {
    if (total == null || total <= 0 || values.isEmpty()) return null
    var remaining = total
    val fractions = mutableListOf<Double>()
    for (value in values) {
        if (value == null || value < 0 || value > remaining) return null
        remaining -= value
        fractions += value.toDouble() / total
    }
    return MemoryCompositionSegments(fractions, remaining)
}

/** PSS totals are never synthesized from Android's summary categories or a partial process set. */
internal fun combineAppMemory(ui: JSONObject?, runtime: JSONObject?): JSONObject? {
    if (ui == null || runtime == null || ui.optString("basis") != "pss" || runtime.optString("basis") != "pss") return null
    return JSONObject().put("basis", "pss").apply {
        for (key in appMemoryKeys) {
            val a = ui.memoryBytes(key)
            val b = runtime.memoryBytes(key)
            put(key, if (a != null && b != null && a <= Long.MAX_VALUE - b) a + b else JSONObject.NULL)
        }
    }
}

internal data class DeviceMemory(val total: Long, val available: Long) {
    val used get() = total - available
    val usedRatio get() = used.toDouble() / total
    companion object {
        fun from(total: Long, available: Long): DeviceMemory? =
            if (total > 0 && available in 0..total) DeviceMemory(total, available) else null
    }
}
internal data class MemorySnapshot(val device: DeviceMemory?, val ui: JSONObject?, val runtime: JSONObject?,
    val sampledAt: Long, val app: JSONObject? = combineAppMemory(ui, runtime))

internal fun measureMemory(context: Context, runtimeMemory: () -> JSONObject?): MemorySnapshot {
    val device = runCatching {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        DeviceMemory.from(info.totalMem, info.availMem)
    }.getOrNull()
    val ui = currentAppProcessMemory()
    val runtime = runCatching(runtimeMemory).getOrNull()
    return MemorySnapshot(device, ui, runtime, android.os.SystemClock.elapsedRealtime())
}
