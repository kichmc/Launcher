package com.kich.launcher.ui.lock

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Android-style 3x3 pattern pad. Dots are numbered 0..8 row-major. A line that passes over an unvisited
 * dot picks that dot up first, as the system pattern lock does.
 *
 * The pad never stores a pattern: it reports the finished dot list once through [onPatternComplete] and
 * then forgets it. With [showTraceAfterLift] false the drawn path disappears the moment the finger lifts
 * (use that for verification); with true it stays for a short moment (useful while setting a pattern).
 */
@Composable
fun PatternPad(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    showTraceAfterLift: Boolean = false,
    dotColor: Color = Color.White,
    errorColor: Color = Color(0xFFFF6B6B),
    onPatternStart: () -> Unit = {},
    onPatternComplete: (List<Int>) -> Unit,
) {
    val path = remember { mutableStateListOf<Int>() }
    var finger by remember { mutableStateOf<Offset?>(null) }
    var lifted by remember { mutableStateOf(false) }
    var sizePx by remember { mutableStateOf(Size.Zero) }

    LaunchedEffect(lifted) {
        if (lifted) { delay(650); path.clear(); lifted = false }
    }

    fun center(index: Int): Offset {
        val cw = sizePx.width / 3f; val ch = sizePx.height / 3f
        return Offset((index % 3 + 0.5f) * cw, (index / 3 + 0.5f) * ch)
    }

    fun hit(p: Offset): Int? {
        if (sizePx.width <= 0f) return null
        val radius = min(sizePx.width, sizePx.height) / 3f * 0.36f
        for (i in 0..8) if (hypot(center(i).x - p.x, center(i).y - p.y) <= radius) return i
        return null
    }

    fun addDot(dot: Int) {
        if (dot in path) return
        val last = path.lastOrNull()
        if (last != null) {
            val dr = abs(last / 3 - dot / 3); val dc = abs(last % 3 - dot % 3)
            if (dr % 2 == 0 && dc % 2 == 0 && (dr == 2 || dc == 2)) {
                val mid = ((last / 3 + dot / 3) / 2) * 3 + (last % 3 + dot % 3) / 2
                if (mid !in path) path.add(mid)
            }
        }
        path.add(dot)
    }

    val color = if (isError) errorColor else dotColor

    Canvas(
        modifier
            .aspectRatio(1f)
            .onSizeChanged { sizePx = Size(it.width.toFloat(), it.height.toFloat()) }
            .semantics { contentDescription = "Pattern pad. Drag across the dots to draw your pattern." }
            .pointerInput(enabled, sizePx) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    path.clear(); lifted = false
                    onPatternStart()
                    finger = down.position
                    hit(down.position)?.let(::addDot)
                    down.consume()
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        finger = change.position
                        hit(change.position)?.let(::addDot)
                        change.consume()
                    } while (event.changes.any { it.pressed })
                    finger = null
                    val result = path.toList()
                    if (result.isNotEmpty()) {
                        if (showTraceAfterLift) lifted = true else path.clear()
                        onPatternComplete(result)
                    }
                }
            },
    ) {
        val stroke = 6.dp.toPx()
        for (i in 0 until path.size - 1) {
            drawLine(color.copy(alpha = 0.85f), center(path[i]), center(path[i + 1]), stroke, StrokeCap.Round)
        }
        val tail = finger
        if (tail != null && path.isNotEmpty()) drawLine(color.copy(alpha = 0.5f), center(path.last()), tail, stroke, StrokeCap.Round)
        for (i in 0..8) {
            val selected = i in path
            if (selected) drawCircle(color.copy(alpha = 0.22f), 26.dp.toPx(), center(i))
            drawCircle(if (selected) color else color.copy(alpha = 0.55f), if (selected) 9.dp.toPx() else 6.dp.toPx(), center(i))
        }
    }
}
