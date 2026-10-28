package dev.terminal.runtime.session

import dev.terminal.protocol.SessionDescriptor
import dev.terminal.protocol.SessionStatus
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

internal data class RetainedSession(val descriptor: SessionDescriptor, val runtime: SessionRuntime,
    val storageId: String, val environmentId: EnvironmentId?, val explicitTitle: Boolean = false)

/** App-private history, never authority to create a process or restore input. */
class RetainedSessionArchive(private val directory: File,
    private val onFailure: (Throwable) -> Unit = {}) {
    private fun file(id: String) = File(directory, java.security.MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } + ".session")

    internal fun saveSafely(session: RetainedSession, states: RuntimeTerminalState): Boolean =
        runCatching { save(session, states) }.onFailure(onFailure).isSuccess

    private fun save(session: RetainedSession, states: RuntimeTerminalState) {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create session archive" }
        val target = file(session.descriptor.id)
        val temporary = File.createTempFile("checkpoint-", ".tmp", directory)
        try {
            temporary.outputStream().use { file ->
                val output = DataOutputStream(file.buffered())
                val d = session.descriptor
                output.writeUTF(JSONObject().apply {
                    put("version", 1); put("id", d.id); put("title", d.title); put("command", d.command)
                    put("cwd", d.cwd); put("createdAtMs", d.createdAtMs); put("rows", d.rows.toInt()); put("cols", d.cols.toInt())
                    put("explicitTitle", session.explicitTitle); put("state", d.state.name); put("exitCode", d.exitCode); put("runtime", session.runtime.name)
                    put("storageId", session.storageId); put("environmentId", session.environmentId?.value ?: JSONObject.NULL)
                }.toString())
                states.save(d.id, output)
                output.flush(); file.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    internal fun restore(states: RuntimeTerminalState): List<RetainedSession> = directory.listFiles().orEmpty()
        .filter { it.name.matches(Regex("[a-f0-9]{64}\\.session")) }.mapNotNull { entry ->
            runCatching {
                require(entry.isFile && entry.length() in 1..64L * 1024 * 1024) { "Invalid session checkpoint size" }
                DataInputStream(entry.inputStream().buffered()).use { input ->
                    val json = JSONObject(input.readUTF()); require(json.getInt("version") == 1)
                    val status = SessionStatus.valueOf(json.getString("state")); require(!status.isLive)
                    val id = json.getString("id"); require(file(id).name == entry.name)
                    val rows = json.getInt("rows"); val cols = json.getInt("cols")
                    require(rows in 1..65535 && cols in 1..65535)
                    val descriptor = SessionDescriptor(id, json.getString("title"), json.getString("command"), json.getString("cwd"),
                        json.getLong("createdAtMs"), rows.toUShort(), cols.toUShort(), status, json.getInt("exitCode"))
                    val retained = RetainedSession(descriptor, SessionRuntime.valueOf(json.getString("runtime")), json.getString("storageId"),
                        if (json.isNull("environmentId")) null else EnvironmentId(json.getString("environmentId")), json.optBoolean("explicitTitle"))
                    states.restore(id, input)
                    retained
                }
            }.onFailure(onFailure).getOrNull()
        }

    internal fun remove(id: String) { Files.deleteIfExists(file(id).toPath()) }
}
