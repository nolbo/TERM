package dev.terminal.runtime.session

import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

/** Persistent creation and appearance preferences; no live process or renderer state belongs here. */
data class SessionProfile(
    val id: String,
    val name: String,
    val runtime: SessionRuntime,
    val shell: String,
    val shellArguments: List<String> = emptyList(),
    val workingDirectory: String = "",
    val environment: Map<String, String> = emptyMap(),
    val startupCommand: String = "",
    val storageId: String = "legacy",
    val icon: String = "auto",
    /** Null follows the global palette; otherwise foreground, background, cursor and 16 ANSI colors. */
    val palette: List<String>? = null,
    /** Authoritative environment reference. runtime/storageId remain for v1 API compatibility. */
    val environmentId: EnvironmentId? = null,
) {
    val builtIn: Boolean get() = SessionRuntime.entries.any { id == builtInId(it) }
    fun resolve(): SessionLaunchSpec = SessionLaunchSpec(this)
    fun resolve(executionEnvironment: Environment): SessionLaunchSpec = SessionLaunchSpec(this, executionEnvironment)
    companion object {
        const val AVF_HOME_DIRECTORY = "/home/droid"
        fun builtInId(runtime: SessionRuntime) = "builtin:${runtime.wireName}"
    }
}

/** Defensive copies make this snapshot independent even of mutable caller collections. */
class SessionLaunchSpec(profile: SessionProfile, val executionEnvironment: Environment? = null) {
    val profileId = profile.id
    val profileName = profile.name
    val runtime = executionEnvironment?.sessionRuntime ?: profile.runtime
    val environmentId = executionEnvironment?.id ?: profile.environmentId
    val shell = profile.shell
    val shellArguments: List<String> = Collections.unmodifiableList(ArrayList(profile.shellArguments))
    val workingDirectory = profile.workingDirectory
    val environment: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(profile.environment))
    val startupCommand = profile.startupCommand
    val storageId = executionEnvironment?.storage?.id ?: profile.storageId
    val icon = profile.icon
    val palette: List<String>? = profile.palette?.let { Collections.unmodifiableList(ArrayList(it)) }
    init {
        require(executionEnvironment == null || profile.environmentId == null || profile.environmentId == executionEnvironment.id) { "Environment reference mismatch" }
        require(palette == null || (palette.size == 19 && palette.all { it.matches(Regex("#[0-9a-fA-F]{6}")) })) { "Invalid profile palette" }
        SessionProfileIcon.validate(icon)
        SessionStorage.validate(storageId)
        require(profileId.isNotBlank() && profileId.length <= 128) { "Invalid profile ID" }
        require(profileName.isNotBlank() && profileName.length <= 128) { "Profile name must contain 1–128 characters" }
        require(shell.startsWith('/') && '\u0000' !in shell) { "Shell must be an absolute path" }
        require(workingDirectory.isEmpty() || workingDirectory.startsWith('/')) { "Working directory must be absolute or empty" }
        require('\u0000' !in workingDirectory && '\u0000' !in startupCommand) { "NUL is not allowed" }
        require(environment.size <= 64 && environment.all { (key, value) ->
            key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && !key.startsWith("DROID_") &&
                !key.startsWith("TERMINAL_USB_") && key != "KITTY_WINDOW_ID" && '\u0000' !in value
        }) { "Invalid or reserved environment variable" }
        SessionCommand.encode(arguments())
        require(toJson().toString().toByteArray().size <= if (environmentId == null) LEGACY_MAX_BYTES else ENVIRONMENT_MAX_BYTES) { "Launch specification exceeds size limit" }
    }
    /** Startup runs once, before exec of the configured interactive shell. Arguments stay literal. */
    fun arguments(): List<String> = if (startupCommand.isBlank()) listOf(shell) + shellArguments else
        listOf(shell, "-c", startupCommand + "\nexec \"\$@\"", shell, shell) + shellArguments

    fun toJson(): JSONObject = JSONObject().put("version", if (executionEnvironment == null) 1 else 2).put("profileId", profileId)
        .put("name", profileName).put("runtime", runtime.wireName).put("shell", shell)
        .put("arguments", JSONArray(shellArguments)).put("cwd", workingDirectory)
        .put("environment", JSONObject(environment)).put("startupCommand", startupCommand).put("storageId", storageId).put("icon", icon)
        .apply {
            palette?.let { put("palette", JSONArray(it)) }
            environmentId?.let { put("environmentId", it.value) }
            executionEnvironment?.let { put("executionEnvironment", it.toJson()) }
        }

    fun toProfile() = SessionProfile(profileId, profileName, runtime, shell, shellArguments, workingDirectory, environment, startupCommand, storageId, icon, palette, environmentId)

    override fun equals(other: Any?): Boolean = other is SessionLaunchSpec && toProfile() == other.toProfile() && executionEnvironment == other.executionEnvironment
    override fun hashCode(): Int = 31 * toProfile().hashCode() + (executionEnvironment?.hashCode() ?: 0)

    companion object {
        const val LEGACY_MAX_BYTES = 32768
        // Metadata overhead must not make an otherwise valid v1 profile unmigratable.
        const val ENVIRONMENT_MAX_BYTES = LEGACY_MAX_BYTES + 2048
        fun fromJson(encoded: String): SessionLaunchSpec {
            require(encoded.toByteArray().size <= ENVIRONMENT_MAX_BYTES) { "Launch specification exceeds size limit" }
            val json = JSONObject(encoded)
            require(json.getInt("version") in 1..2) { "Unsupported profile version" }
            if (!json.has("environmentId")) require(encoded.toByteArray().size <= LEGACY_MAX_BYTES) { "Profile exceeds 32 KiB" }
            val executionEnvironment = if (json.getInt("version") == 2) Environment.fromJson(json.getJSONObject("executionEnvironment")) else null
            val environmentId = json.optString("environmentId").takeIf(String::isNotEmpty)?.let(::EnvironmentId)
            if (executionEnvironment != null) {
                require(environmentId == executionEnvironment.id && json.getString("runtime") == executionEnvironment.sessionRuntime.wireName &&
                    json.getString("storageId") == executionEnvironment.storage.id) { "Environment snapshot mismatch" }
            }
            val args = json.getJSONArray("arguments")
            val env = json.getJSONObject("environment")
            return SessionProfile(json.getString("profileId"), json.getString("name"),
                requireNotNull(SessionRuntime.fromWireName(json.getString("runtime"))), json.getString("shell"),
                List(args.length()) { args.getString(it) }, json.getString("cwd"),
                env.keys().asSequence().associateWith { env.getString(it) }, json.getString("startupCommand"),
                json.optString("storageId", "legacy"), json.optString("icon", "auto"),
                if (!json.has("palette") || json.isNull("palette")) null else json.getJSONArray("palette").let { colors ->
                    List(colors.length()) { index -> require(colors.get(index) is String); colors.getString(index) }
                }, environmentId).let { SessionLaunchSpec(it, executionEnvironment) }
        }
    }
}
