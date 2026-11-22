package com.wakr.app.ui.screens

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import com.wakr.app.core.WorldCity

internal class WorldClockReorder(private val grid: LazyGridState) {
    var coordinates: LayoutCoordinates? = null
    val handles = mutableMapOf<String, LayoutCoordinates>()
    var cityId by mutableStateOf<String?>(null)
        private set
    private var pointer by mutableStateOf(Offset.Zero)
    private var pointerInCard = Offset.Zero
    private var halfCardSize = Offset.Zero
    private var settleProgress by mutableStateOf(0f)

    private fun visibleCard(id: String) = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }

    private fun LazyGridItemInfo.origin() =
        Offset(offset.x.toFloat(), (offset.y + grid.layoutInfo.beforeContentPadding).toFloat())

    fun start(position: Offset): String? {
        val gridCoordinates = coordinates?.takeIf { it.isAttached } ?: return null
        val card = grid.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            val handle = handles[item.key]?.takeIf { it.isAttached } ?: return@firstOrNull false
            position in gridCoordinates.localBoundingBoxOf(handle)
        } ?: return null
        pointer = position
        pointerInCard = position - card.origin()
        halfCardSize = Offset(card.size.width / 2f, card.size.height / 2f)
        settleProgress = 0f
        return (card.key as String).also { cityId = it }
    }

    fun update(position: Offset) { pointer = position }

    fun offset(id: String): Offset {
        if (id != cityId) return Offset.Zero
        val card = visibleCard(id) ?: return Offset.Zero
        return (pointer - pointerInCard - card.origin()) * (1f - settleProgress)
    }

    fun targetIndex(): Int? {
        val center = pointer - pointerInCard + halfCardSize
        // Use measured slot positions so successive pointer events can move past multiple
        // cards even when the grid has yet to lay out the new order.
        return grid.layoutInfo.visibleItemsInfo.asSequence()
            .filter { it.index > 0 }
            .minByOrNull {
                val delta = it.origin() + Offset(it.size.width / 2f, it.size.height / 2f) - center
                delta.getDistanceSquared()
            }?.let { it.index - 1 }
    }

    suspend fun settle() {
        animate(0f, 1f, animationSpec = tween(220)) { value, _ -> settleProgress = value }
        cityId = null
    }

    suspend fun scrollAtEdge(edge: Float, onMove: () -> Unit) {
        grid.scroll(MutatePriority.UserInput) {
            var previousFrame = withFrameNanos { it }
            while (true) {
                val frame = withFrameNanos { it }
                val elapsed = ((frame - previousFrame) / 1_000_000_000f).coerceAtMost(.05f)
                previousFrame = frame
                val bottom = grid.layoutInfo.viewportSize.height.toFloat()
                val band = minOf(edge, bottom / 3f)
                if (band <= 0f) continue
                val speed = when {
                    pointer.y < band -> -((band - pointer.y) / band).coerceAtMost(1f)
                    pointer.y > bottom - band -> ((pointer.y - bottom + band) / band).coerceAtMost(1f)
                    else -> 0f
                }
                if (speed != 0f && scrollBy(speed * edge * 12f * elapsed) != 0f) onMove()
            }
        }
    }
}

internal fun Modifier.cityReorderGesture(
    reorder: WorldClockReorder,
    enabled: Boolean,
    onStart: () -> Unit,
    onMove: (String) -> Unit,
    onFinish: (String, Boolean) -> Unit,
): Modifier = pointerInput(reorder, enabled) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        // Claim touches on handles before the surrounding grid or tab pager starts scrolling.
        val down = awaitFirstDown(pass = PointerEventPass.Initial)
        val id = reorder.start(down.position) ?: return@awaitEachGesture
        down.consume()
        onStart()
        var dropped = false
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.isConsumed) break
                change.consume()
                if (change.position != change.previousPosition) {
                    reorder.update(change.position)
                    onMove(id)
                }
                if (!change.pressed) {
                    dropped = true
                    break
                }
            }
        } finally {
            onFinish(id, dropped)
        }
    }
}

internal fun List<WorldCity>.moveCity(from: Int, to: Int): List<WorldCity> =
    toMutableList().apply { add(to, removeAt(from)) }
