package com.magictablet.game.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val INITIAL_DELAY_MS = 400L
private const val REPEAT_MS = 120L
private const val ACCEL_AFTER_MS = 2000L
private const val FAST_REPEAT_MS = 40L

/** Fires [onClick] once on press, then auto-repeats (accelerating) while held. */
fun Modifier.holdRepeatClick(onClick: () -> Unit): Modifier = composed {
    val current = rememberUpdatedState(onClick)
    pointerInput(Unit) {
        coroutineScope {
            awaitEachGesture {
                // requireUnconsumed = true (default): if an overlaid clickable control
                // (the ⚙ hub or the "▴ counters" chevron) already consumed this down,
                // the tap zone ignores it instead of also nudging life by ±1.
                awaitFirstDown()
                current.value()
                val job = this@coroutineScope.launch {
                    delay(INITIAL_DELAY_MS)
                    var elapsed = 0L
                    var interval = REPEAT_MS
                    while (isActive) {
                        current.value()
                        delay(interval)
                        elapsed += interval
                        if (elapsed >= ACCEL_AFTER_MS) interval = FAST_REPEAT_MS
                    }
                }
                waitForUpOrCancellation()
                job.cancel()
            }
        }
    }
}
