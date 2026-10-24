package cn.edu.ahtcm.schedule.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween

/** Capped travel keeps desktop transitions restrained and phone transitions legible. */
internal fun directionalSlide(forward: Boolean, travelPixels: Int): ContentTransform {
    val direction = if (forward) 1 else -1
    val easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    return (fadeIn(tween(220, delayMillis = 30)) +
        slideInHorizontally(tween(300, easing = easing)) { direction * minOf(it / 5, travelPixels) }) togetherWith
        (fadeOut(tween(150)) +
            slideOutHorizontally(tween(240, easing = easing)) { -direction * minOf(it / 8, travelPixels / 2) })
}
