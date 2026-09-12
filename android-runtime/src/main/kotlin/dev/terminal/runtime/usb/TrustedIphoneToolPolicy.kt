package dev.terminal.runtime.usb

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes

internal object TrustedIphoneToolPolicy {
    const val SERVICE_REVISION = 2
    enum class Phase { IDLE, WAITING_FOR_USB_PERMISSION, CONNECTING, RUNNING, COMPLETED, CANCELED, FAILED }
    enum class Error {
        USB_HOST_UNAVAILABLE, USB_PERMISSION_DENIED, UNSUPPORTED_USB_DEVICE,
        CANCELED, USB_DETACHED, DEVICE_BUSY, WORKER_AUTHENTICATION, WORKER_PROTOCOL,
        DEVICE_LOCKED, TRUST_DENIED, PAIRING_FAILED, UNSUPPORTED_IOS_PAIRING, NETWORK, ANISETTE,
        PAIR_RECORD_READ_FAILED, PAIR_RECORD_INVALID, PAIR_RECORD_SAVE_FAILED, PAIR_RECORD_REJECTED,
        PAIRING_TRANSPORT, PAIRING_RESPONSE, PAIRING_TIMEOUT, PAIR_SESSION_FAILED,
        APPLE_CREDENTIALS, TWO_FACTOR, NO_DEVELOPER_TEAM, CERTIFICATE_LIMIT, APP_ID_LIMIT,
        PROVISIONING, IOS_CERTIFICATE_REJECTED, DOWNLOAD, SIGNING, INSTALLATION_PROXY,
        RP_PAIRING, WIFI_DEBUGGING, AFC_PLACEMENT, INPUT_TIMEOUT, TWO_FACTOR_TIMEOUT, ARTIFACT_CLEANUP,
    }
    fun validateRequest(action: String, keys: Set<String>) {
        val extra = when (action) {
            "list" -> emptySet()
            "check" -> setOf("device")
            "start" -> setOf("device", "tool")
            "status", "cancel" -> setOf("operation")
            "select" -> setOf("operation", "choices")
            else -> error("UNSUPPORTED_TOOL_OPERATION")
        }
        val expected = setOf("version", "token", "op", "action") + extra
        require(keys == expected) { "INVALID_TOOL_REQUEST" }
    }
    fun deleteTemporary(root: File, candidate: File) {
        val rootPath = root.canonicalFile.toPath()
        val target = candidate.absoluteFile.toPath().normalize()
        require(target.parent == rootPath && target != rootPath)
        if (!Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(target, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file); return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exception: java.io.IOException?): FileVisitResult {
                if (exception != null) throw exception
                Files.delete(dir); return FileVisitResult.CONTINUE
            }
        }) // never FOLLOW_LINKS
    }
}

internal class TrustedIphoneToolStateMachine {
    var phase = TrustedIphoneToolPolicy.Phase.IDLE
        private set
    fun transition(next: TrustedIphoneToolPolicy.Phase) {
        val allowed = when (phase) {
            TrustedIphoneToolPolicy.Phase.IDLE -> TrustedIphoneToolPolicy.Phase.WAITING_FOR_USB_PERMISSION
            TrustedIphoneToolPolicy.Phase.WAITING_FOR_USB_PERMISSION -> TrustedIphoneToolPolicy.Phase.CONNECTING
            TrustedIphoneToolPolicy.Phase.CONNECTING -> TrustedIphoneToolPolicy.Phase.RUNNING
            TrustedIphoneToolPolicy.Phase.RUNNING -> TrustedIphoneToolPolicy.Phase.COMPLETED
            else -> null
        }
        check(next == allowed)
        phase = next
    }
    fun stop(error: TrustedIphoneToolPolicy.Error) {
        if (phase !in setOf(TrustedIphoneToolPolicy.Phase.COMPLETED, TrustedIphoneToolPolicy.Phase.CANCELED, TrustedIphoneToolPolicy.Phase.FAILED))
            phase = if (error == TrustedIphoneToolPolicy.Error.CANCELED) TrustedIphoneToolPolicy.Phase.CANCELED else TrustedIphoneToolPolicy.Phase.FAILED
    }
}
