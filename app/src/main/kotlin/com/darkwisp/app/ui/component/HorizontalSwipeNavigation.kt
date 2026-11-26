package com.darkwisp.app.ui.component

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

/**
 * Adds one-step horizontal section navigation without reacting to vertical scrolling.
 * The caller owns the section state and supplies a platform haptic callback.
 */
fun Modifier.horizontalSectionSwipe(
    selectedIndex: Int,
    itemCount: Int,
    onSelectionChanged: (Int) -> Unit,
    onHaptic: () -> Unit,
    thresholdDp: Float = 72f
): Modifier = pointerInput(selectedIndex, itemCount, thresholdDp) {
    var horizontalDrag = 0f
    detectHorizontalDragGestures(
        onHorizontalDrag = { change, dragAmount ->
            horizontalDrag += dragAmount
        },
        onDragEnd = {
            val thresholdPx = thresholdDp * density
            if (abs(horizontalDrag) >= thresholdPx) {
                val nextIndex = if (horizontalDrag < 0f) selectedIndex + 1 else selectedIndex - 1
                if (nextIndex in 0 until itemCount && nextIndex != selectedIndex) {
                    onSelectionChanged(nextIndex)
                    onHaptic()
                }
            }
            horizontalDrag = 0f
        },
        onDragCancel = { horizontalDrag = 0f }
    )
}
