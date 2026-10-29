package app.fieldwatch.ui.component

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import app.fieldwatch.domain.MapFrame
import kotlin.math.abs

/**
 * The heading drawn on the map. A new GPS course arrives about once a second.
 * The picture turns through that gap, and the ground is laid out at the angle
 * on screen so the edges stay filled.
 */
@Composable
internal fun rememberEasedCourse(target: Double): Double {
    val anim = remember {
        Animatable(if (target.isFinite()) target.toFloat() else Float.NaN)
    }
    val lastAt = remember { mutableLongStateOf(0L) }
    LaunchedEffect(target) {
        if (!target.isFinite()) {
            anim.snapTo(Float.NaN)
            lastAt.longValue = 0L
            return@LaunchedEffect
        }
        val from = anim.value.toDouble()
        val now = SystemClock.uptimeMillis()
        val gap = if (lastAt.longValue == 0L) 0L else now - lastAt.longValue
        lastAt.longValue = now
        if (!from.isFinite()) {
            anim.snapTo(target.toFloat())
            return@LaunchedEffect
        }
        val turn = MapFrame.shortestTurn(from, target)
        if (abs(turn) < 1.0) {
            anim.snapTo(wrapCourse(target).toFloat())
        } else {
            anim.animateTo(
                (from + turn).toFloat(),
                tween(
                    durationMillis = MapFrame.courseTurnMs(gap),
                    easing = LinearEasing,
                ),
            )
            anim.snapTo(wrapCourse(anim.value.toDouble()).toFloat())
        }
    }
    if (!target.isFinite()) return Double.NaN
    val shown = anim.value.toDouble()
    return if (shown.isFinite()) wrapCourse(shown) else target
}

private fun wrapCourse(deg: Double): Double {
    val turned = deg % 360.0
    return if (turned < 0.0) turned + 360.0 else turned
}
