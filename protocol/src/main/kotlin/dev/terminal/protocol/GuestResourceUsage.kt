package dev.terminal.protocol

/** Aggregate guest metrics only; no process IDs, paths or terminal content. */
data class GuestResourceUsage(val totalBytes: Long, val availableBytes: Long,
    val swapTotalBytes: Long, val swapFreeBytes: Long, val cpuCount: Int,
    val loadMilli: Int, val memoryPressureMilli: Int) {
    init {
        require(totalBytes > 0 && availableBytes in 0..totalBytes)
        require(swapTotalBytes >= 0 && swapFreeBytes in 0..swapTotalBytes)
        require(cpuCount in 1..4096 && loadMilli >= 0 && memoryPressureMilli in -1..100_000)
    }
}
