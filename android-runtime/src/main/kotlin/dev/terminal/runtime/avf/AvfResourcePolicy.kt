package dev.terminal.runtime.avf

/** The platform ABI supports one vCPU or host topology, never an arbitrary count. */
object AvfResourcePolicy {
    const val SINGLE_CPU = 1
    const val HOST_CPUS = 2
    const val AUTOMATIC_MEMORY = 0
    fun cpuTopology(saved: Int): Int = if (saved <= 1) SINGLE_CPU else HOST_CPUS

    /** Keep at least two thirds of host RAM outside the default VM; preserve manual budgets. */
    fun memoryMib(saved: Int, hostBytes: Long, availableBytes: Long = Long.MAX_VALUE): Int {
        if (saved != AUTOMATIC_MEMORY) return saved.coerceIn(512, 32768)
        val target = if (hostBytes <= 0) 2048 else
            ((hostBytes / (1024 * 1024) / 3 / 256) * 256).coerceIn(1024, 4096).toInt()
        if (availableBytes == Long.MAX_VALUE) return target
        val availableBudget = (availableBytes.coerceAtLeast(0) / (1024 * 1024) / 2 / 256) * 256
        require(availableBudget >= 1024) { "자동 AVF 메모리가 부족합니다. 다른 환경을 중지하거나 메모리 설정을 조정하세요." }
        return minOf(target.toLong(), availableBudget).toInt()
    }
}
