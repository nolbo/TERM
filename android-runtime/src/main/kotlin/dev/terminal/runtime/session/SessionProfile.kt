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
) {
    val builtIn: Boolean get() = id == builtInId(runtime)
    fun resolve(): SessionLaunchSpec = SessionLaunchSpec(this)
    companion object {
        fun builtInId(runtime: SessionRuntime) = "builtin:${runtime.wireName}"
    }
}

/** Defensive copies make this snapshot independent even of mutable caller collections. */
class SessionLaunchSpec(profile: SessionProfile) {
    val profileId = profile.id
    val profileName = profile.name
    val runtime = profile.runtime
    val shell = profile.shell
    val shellArguments: List<String> = Collections.unmodifiableList(ArrayList(profile.shellArguments))
    val workingDirectory = profile.workingDirectory
    val environment: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(profile.environment))
    val startupCommand = profile.startupCommand
    val storageId = profile.storageId
    val icon = profile.icon
    val palette: List<String>? = profile.palette?.let { Collections.unmodifiableList(ArrayList(it)) }
    init {
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
        require(toJson().toString().toByteArray().size <= 32768) { "Profile exceeds 32 KiB" }
    }
    /** Startup runs once, before exec of the configured interactive shell. Arguments stay literal. */
    fun arguments(): List<String> = if (startupCommand.isBlank()) listOf(shell) + shellArguments else
        listOf(shell, "-c", startupCommand + "\nexec \"\$@\"", shell, shell) + shellArguments

    fun toJson(): JSONObject = JSONObject().put("version", 1).put("profileId", profileId)
        .put("name", profileName).put("runtime", runtime.wireName).put("shell", shell)
        .put("arguments", JSONArray(shellArguments)).put("cwd", workingDirectory)
        .put("environment", JSONObject(environment)).put("startupCommand", startupCommand).put("storageId", storageId).put("icon", icon)
        .apply { palette?.let { put("palette", JSONArray(it)) } }

    fun toProfile() = SessionProfile(profileId, profileName, runtime, shell, shellArguments, workingDirectory, environment, startupCommand, storageId, icon, palette)

    override fun equals(other: Any?): Boolean = other is SessionLaunchSpec && toProfile() == other.toProfile()
    override fun hashCode(): Int = toProfile().hashCode()

    companion object {
        fun fromJson(encoded: String): SessionLaunchSpec {
            require(encoded.toByteArray().size <= 32768) { "Profile exceeds 32 KiB" }
            val json = JSONObject(encoded)
            require(json.getInt("version") == 1) { "Unsupported profile version" }
            val args = json.getJSONArray("arguments")
            val env = json.getJSONObject("environment")
            return SessionProfile(json.getString("profileId"), json.getString("name"),
                requireNotNull(SessionRuntime.fromWireName(json.getString("runtime"))), json.getString("shell"),
                List(args.length()) { args.getString(it) }, json.getString("cwd"),
                env.keys().asSequence().associateWith { env.getString(it) }, json.getString("startupCommand"),
                json.optString("storageId", "legacy"), json.optString("icon", "auto"),
                if (!json.has("palette") || json.isNull("palette")) null else json.getJSONArray("palette").let { colors ->
                    List(colors.length()) { index -> require(colors.get(index) is String); colors.getString(index) }
                }).resolve()
        }
    }
}
