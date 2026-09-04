package dev.terminal.runtime.image

import android.content.Context
import dev.terminal.image.DebianImageVerifier
import dev.terminal.image.ImageVerification
import java.nio.file.Path

/** First-run automation: valid local image wins; otherwise enqueue approved image install. */
class RuntimeSetupCoordinator(private val context: Context) {
    fun ensureImage(imageRoot: Path, releaseId: String): SetupState = when (val verification = DebianImageVerifier.verify(imageRoot)) {
        is ImageVerification.Ready -> SetupState.Ready
        is ImageVerification.Invalid -> {
            DebianImageDownloadWorker.enqueue(context, releaseId)
            SetupState.Downloading(verification.reason)
        }
    }
}

sealed interface SetupState {
    data object Ready : SetupState
    data class Downloading(val previousProblem: String) : SetupState
}
