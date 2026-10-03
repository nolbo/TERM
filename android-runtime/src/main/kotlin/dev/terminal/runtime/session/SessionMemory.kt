package dev.terminal.runtime.session

import java.io.File

/** Host-only measurement interface. Never sends PIDs or proc paths to a guest/UI. */
interface SessionMemorySource {
    fun memoryRoots(): Map<String, ProcessMemoryIdentity?>
}

data class ProcessMemoryIdentity(val pid: Int, val startTicks: Long)
internal data class ProcessMemoryStat(val identity: ProcessMemoryIdentity, val parent: Int,
    val linuxSession: Int, val residentPages: Long)

internal fun parseProcessMemoryStat(line: String): ProcessMemoryStat? = runCatching {
    val pid = line.substringBefore(' ').toInt()
    // comm can contain spaces and parentheses. Fields after the final ')' start at state (3).
    val fields = line.substring(line.lastIndexOf(')') + 2).trim().split(Regex("\\s+"))
    require(pid > 0 && fields.size >= 22)
    ProcessMemoryStat(ProcessMemoryIdentity(pid, fields[19].toLong()), fields[1].toInt(),
        fields[3].toInt(), fields[21].toLong().coerceAtLeast(0))
}.getOrNull()

fun processMemoryIdentity(pid: Int): ProcessMemoryIdentity? = readProcessMemoryStat(File("/proc/$pid"))?.identity

/**
 * Call before starting waitFor(): the unreaped child cannot have its PID reused.
 * A release child inherits the non-dumpable app state until exec, so hidepid can
 * temporarily hide its stat file. Retry for up to about one second without
 * changing dumpability or treating a permanent access restriction as zero RSS.
 */
internal fun awaitProcessMemoryIdentity(
    pid: Int,
    readIdentity: (Int) -> ProcessMemoryIdentity? = ::processMemoryIdentity,
    pause: () -> Unit = { Thread.sleep(10) },
): ProcessMemoryIdentity? {
    repeat(100) { attempt ->
        if (attempt > 0) pause()
        readIdentity(pid)?.let { return it }
    }
    return null
}

private fun readProcessMemoryStat(directory: File): ProcessMemoryStat? = runCatching {
    File(directory, "stat").bufferedReader().use { reader ->
        val buffer = CharArray(8192)
        val count = reader.read(buffer)
        if (count <= 0) null else parseProcessMemoryStat(String(buffer, 0, count))
    }
}.getOrNull()

/** Root PID reuse cannot attribute another process to an old terminal session. */
internal fun sessionResidentBytes(root: ProcessMemoryIdentity, processes: List<ProcessMemoryStat>, pageSize: Long): Long? {
    if (processes.none { it.identity == root }) return null
    val children = processes.groupBy { it.parent }
    val included = mutableSetOf(root.pid)
    val queue = java.util.ArrayDeque<Int>().apply { add(root.pid) }
    while (queue.isNotEmpty()) children[queue.removeFirst()].orEmpty().forEach {
        if (included.add(it.identity.pid)) queue.add(it.identity.pid)
    }
    return processes.filter { it.identity.pid in included || it.linuxSession == root.pid }
        .sumOf { it.residentPages * pageSize }
}

/** Bounded, best-effort snapshot of readable processes. RSS includes shared pages per process. */
fun measureSessionMemory(roots: Map<String, ProcessMemoryIdentity?>): Map<String, Long?> {
    val processes = File("/proc").listFiles().orEmpty().asSequence()
        .filter { it.name.toIntOrNull() != null }.take(8192).mapNotNull(::readProcessMemoryStat).toList()
    val pageSize = android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)
    return roots.mapValues { (_, root) -> root?.let { sessionResidentBytes(it, processes, pageSize) } }
}
