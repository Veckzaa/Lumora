package com.lumora.adapter

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.widget.HorizontalScrollView

/**
 * A guide row's programme scroller that can leave its scroll alone when one block takes focus.
 *
 * HorizontalScrollView scrolls every newly focused child fully into view - to its start when it
 * is wider than the screen - and the guide shares that scroll with every row and the time ruler.
 * Moving up or down onto a long programme therefore slid the whole timeline sideways on a
 * vertical press (view 20:47 -> 22:34 on the FireTV_API28 AVD). setRevealOnFocusHint(false)
 * would do it, but only from API 26, and Fire OS 6 is API 25.
 */
class GuideProgramScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : HorizontalScrollView(context, attrs) {

    /** A block focus moved onto from the row above or below, already partly on screen: focusing
     *  it doesn't scroll. Cleared when it loses focus. */
    var holdScrollFor: View? = null

    private val heldRect = Rect()

    /** The offset the guide's rows share, as last given to [follow] or scrolled to here. */
    private var followX = 0

    /**
     * Scrolls to the guide's shared offset, and again once laid out if that isn't possible yet:
     * a row created mid-scroll gets the offset before its first layout, when its content is still
     * 0 wide and scrollTo clamps to 0. That row then sat at the timeline's start, and the first
     * scroll it made - focus landing in it - pulled every row and the ruler back there (view
     * 10:45 -> 19:00 moving down onto a new row, FireTV_API28 AVD).
     */
    fun follow(x: Int) {
        followX = x
        if (scrollX != x) scrollTo(x, 0)
    }

    /**
     * True while this view lays itself out. HorizontalScrollView re-applies its offset there,
     * clamped to the width the row has in that pass - and the guide list lays its rows out inside
     * measure passes too, where that width can be the whole content's, clamping to 0. Shared,
     * that clamp pulled every row and the ruler back to the timeline's start (view 10:45 -> 19:00
     * on a down press, FireTV_API28 AVD), so a scroll made here is neither shared nor kept.
     */
    var inOwnLayout = false
        private set

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        inOwnLayout = true
        try {
            super.onLayout(changed, l, t, r, b)
        } finally {
            inOwnLayout = false
        }
        if (scrollX != followX) scrollTo(followX, 0)
    }

    /**
     * Blocks scrolled out of view are no target for a focus search that starts outside this row.
     * Their rects lie beyond everything on screen, so with the guide scrolled to 22:45, LEFT from a
     * channel's star picked that row's 18:00 programme over the category sidebar and scrolled the
     * whole guide back to it. Within the row they stay: that's how left/right reach the programme
     * just past the edge.
     */
    override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
        val start = views.size
        super.addFocusables(views, direction, focusableMode)
        if (hasFocus()) return
        for (i in views.size - 1 downTo start) {
            val block = views[i]
            if (block.right <= scrollX || block.left >= scrollX + width) views.removeAt(i)
        }
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (!inOwnLayout) followX = l
    }

    // Matched by rect, not findFocus(): the scroll is asked for before this view records which
    // child has focus.
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        val held = holdScrollFor
        if (held != null && held.parent?.parent === this) {
            held.getDrawingRect(heldRect)
            offsetDescendantRectToMyCoords(held, heldRect)
            if (heldRect == rect) return 0
        }
        return super.computeScrollDeltaToGetChildRectOnScreen(rect)
    }
}
