package io.github.digipr1me.digiautotap

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout

/**
 * The fully automatic order as a list the player rearranges by dragging
 * (2026-10-01; the ▲ and ▼ buttons before, one step per tap and the sheet
 * built again after each). A finger on a row's grip lifts the row: it
 * follows the finger, the rows it passes make room, and letting go puts it
 * where it stands and says so once ([onMoved], from and to). The list moves
 * its own rows; whoever built them saves the order and numbers them again.
 *
 * Only the grip starts a drag, so that the rest of the row scrolls the sheet
 * as before. The grip asks every parent to keep its hands off for the
 * gesture (`requestDisallowInterceptTouchEvent`): the sheet's scroll, and
 * the sheet's own pull down, which would otherwise take a drag downwards
 * from its top ([SheetScroll]).
 */
class OrderList(ctx: Context, private val onMoved: (from: Int, to: Int) -> Unit) : LinearLayout(ctx) {
    init { orientation = VERTICAL }

    private var from = -1
    private var to = -1
    private var downY = 0f

    /** A row of the list, and the part of it a finger lifts it by. */
    fun addRow(row: View, grip: View) {
        addView(row)
        grip.setOnTouchListener { _, e -> drag(row, e) }
    }

    private fun drag(row: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                from = indexOfChild(row)
                to = from
                downY = e.rawY
                row.translationZ = 8 * resources.displayMetrics.density
                row.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
            MotionEvent.ACTION_MOVE -> {
                if (from < 0) return true
                // The row stays inside the list: dragged past either end it
                // stops at the end, and the slot is the one under its middle.
                val dy = (e.rawY - downY)
                    .coerceIn((-row.top).toFloat(), (height - row.bottom).toFloat())
                row.translationY = dy
                val middle = row.top + dy + row.height / 2f
                to = (0 until childCount).count { i ->
                    i != from && getChildAt(i).let { it.top + it.height / 2f } < middle
                }
                for (i in 0 until childCount) {
                    if (i == from) continue
                    val c = getChildAt(i)
                    val shift = when {
                        i in (from + 1)..to -> -row.height.toFloat()
                        i in to until from -> row.height.toFloat()
                        else -> 0f
                    }
                    if (c.translationY != shift) c.animate().translationY(shift).setDuration(120)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (from < 0) return true
                val a = from
                val b = if (e.actionMasked == MotionEvent.ACTION_UP) to else from
                from = -1
                for (i in 0 until childCount) getChildAt(i).apply {
                    animate().cancel()
                    translationY = 0f
                    translationZ = 0f
                }
                if (b != a) {
                    removeViewAt(a)
                    addView(row, b)
                    onMoved(a, b)
                }
            }
        }
        return true
    }
}
