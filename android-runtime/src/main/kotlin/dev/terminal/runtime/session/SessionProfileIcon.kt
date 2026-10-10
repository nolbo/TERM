package dev.terminal.runtime.session

import dev.terminal.runtime.icon.BuiltInIcon

/** Stable, path-free icon identities. Image bytes belong to the app, never to Binder payloads. */
object SessionProfileIcon {
    val builtIns = BuiltInIcon.ids
    private val imagePattern = Regex("image:[0-9a-f]{64}:(mask|color)")
    fun validate(value: String) {
        require(value == "auto" || value in builtIns || isImage(value)) { "Invalid profile icon" }
    }
    fun isImage(value: String): Boolean = imagePattern.matches(value)
    fun resolve(value: String, runtime: SessionRuntime): String = if (value != "auto") value else when (runtime) {
        SessionRuntime.NATIVE -> "native"
        SessionRuntime.ROOTLESS -> "debian"
        SessionRuntime.ROOTLESS_UBUNTU -> "ubuntu"
        SessionRuntime.ROOTLESS_ARCH -> "arch"
        SessionRuntime.AVF -> "avf"
    }
}
