@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.terminal.app.R
import java.util.Locale
import kotlinx.coroutines.delay
import org.json.JSONObject

data class RootlessProgressUi(
    val phase: String,
    val label: String,
    val detail: String,
    val fraction: Float?,
    val completedBytes: Long?,
    val totalBytes: Long?,
    val startedAtElapsedMs: Long,
    val updatedAtElapsedMs: Long,
    val finishedAtElapsedMs: Long?,
)

internal fun parseRootlessProgress(entry: JSONObject?): RootlessProgressUi? {
    val progress = entry?.optJSONObject("progress") ?: return null
    val label = progress.optString("label").takeIf { it.isNotBlank() } ?: return null
    val fraction = progress.optDouble("fraction", Double.NaN).toFloat().takeIf { it.isFinite() && it in 0f..1f }
    fun positiveLong(key: String) = progress.optLong(key, -1).takeIf { it >= 0 }
    val started = positiveLong("startedAtElapsedMs") ?: return null
    val updated = positiveLong("updatedAtElapsedMs")?.takeIf { it >= started } ?: return null
    return RootlessProgressUi(progress.optString("phase"), label.take(120), progress.optString("detail").take(240),
        fraction, positiveLong("completedBytes"), positiveLong("totalBytes"),
        started, updated, positiveLong("finishedAtElapsedMs"))
}

/** Progress applies to the named phase only; no guessed overall percentage or completion time. */
@Composable
internal fun RootlessProgressIndicator(progress: RootlessProgressUi, running: Boolean, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(running, progress.startedAtElapsedMs) {
        now = SystemClock.elapsedRealtime()
        while (running) { delay(1000); now = SystemClock.elapsedRealtime() }
    }
    val end = if (running) now else progress.finishedAtElapsedMs ?: progress.updatedAtElapsedMs
    val seconds = ((end - progress.startedAtElapsedMs).coerceAtLeast(0) / 1000)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (running) {
            val fraction = progress.fraction
            if (fraction == null) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            else LinearWavyProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
        val bytes = progress.completedBytes
        val numbers = buildList {
            progress.fraction?.let { add(stringResource(R.string.rootless_stage_percent, (it * 100).toInt())) }
            if (bytes != null) {
                fun size(value: Long) = String.format(Locale.getDefault(), "%.1f MiB", value / (1024.0 * 1024))
                add(if (progress.totalBytes != null) "${size(bytes)} / ${size(progress.totalBytes)}" else size(bytes))
            }
        }
        if (numbers.isNotEmpty()) Text(numbers.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.rootless_elapsed, seconds / 60, seconds % 60), style = MaterialTheme.typography.bodySmall)
        if (running && now - progress.updatedAtElapsedMs >= 20_000) {
            Text(stringResource(R.string.rootless_last_progress, (now - progress.updatedAtElapsedMs) / 1000),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
