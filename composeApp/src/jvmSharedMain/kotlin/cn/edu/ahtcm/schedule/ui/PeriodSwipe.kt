package cn.edu.ahtcm.schedule.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Horizontal drags turn one page; vertical scrolling and short taps keep their handlers. */
@Composable
internal fun Modifier.periodSwipe(onPrevious: () -> Unit, onNext: () -> Unit): Modifier {
    val previous by rememberUpdatedState(onPrevious)
    val next by rememberUpdatedState(onNext)
    val threshold = with(LocalDensity.current) { 48.dp.toPx() }
    return pointerInput(threshold) {
        var distance = 0f
        detectHorizontalDragGestures(
            onDragStart = { distance = 0f },
            onDragCancel = { distance = 0f },
            onDragEnd = {
                when {
                    distance > threshold -> previous()
                    distance < -threshold -> next()
                }
                distance = 0f
            },
            onHorizontalDrag = { change, amount -> change.consume(); distance += amount }
        )
    }
}
