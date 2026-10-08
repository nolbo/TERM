@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app

import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.material3.MotionScheme
import com.termux.view.TerminalView
import kotlin.math.ceil

/** Platform animator clock, Compose theme curves. ValueAnimator applies system duration scaling. */
internal class HistoryScrollbarMotion(scheme: MotionScheme) {
    val spatial = platformAnimator(scheme.defaultSpatialSpec<Float>())
    val fastSpatial = platformAnimator(scheme.fastSpatialSpec<Float>())
    val effects = platformAnimator(scheme.fastEffectsSpec<Float>())

    fun applyTo(view: TerminalView) = view.setHistoryScrollbarMotion(spatial, fastSpatial, effects)
}

private fun platformAnimator(spec: FiniteAnimationSpec<Float>): TerminalView.HistoryScrollbarAnimatorFactory {
    val vectorized = spec.vectorize(Float.VectorConverter)
    return TerminalView.HistoryScrollbarAnimatorFactory { initial, target, velocity, frame ->
        val start = AnimationVector1D(initial)
        val end = AnimationVector1D(target)
        val speed = AnimationVector1D(velocity)
        val nanos = vectorized.getDurationNanos(start, end, speed).coerceAtLeast(1)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ceil(nanos / 1_000_000.0).toLong().coerceAtLeast(1)
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                val fraction = animation.animatedFraction
                if (fraction <= 0f) {
                    frame.update(initial, velocity)
                } else if (fraction >= 1f) {
                    frame.update(target, 0f)
                } else {
                    val time = (nanos * fraction).toLong()
                    frame.update(
                        vectorized.getValueFromNanos(time, start, end, speed).value,
                        vectorized.getVelocityFromNanos(time, start, end, speed).value,
                    )
                }
            }
        }
    }
}
