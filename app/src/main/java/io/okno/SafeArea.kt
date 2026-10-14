package io.okno

import android.graphics.Rect
import android.os.Build
import android.view.RoundedCorner
import android.view.View
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Parts of the screen that cannot show content well: the rounded display
 * corners and the camera cutout. A window's close button drawn right at the
 * top corner of the desktop would be cut off by the rounding.
 */
object SafeArea {

    /**
     * How far content must stay from each side of [view] so that none of it
     * falls under a rounded corner or the cutout. Zero on sides away from them.
     */
    fun insets(view: View, out: Rect) {
        out.setEmpty()
        val insets = view.rootWindowInsets ?: return
        val pos = IntArray(2)
        view.getLocationInWindow(pos)
        val left = pos[0]
        val top = pos[1]
        val right = left + view.width
        val bottom = top + view.height

        if (Build.VERSION.SDK_INT >= 31) {
            val corners = intArrayOf(
                RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT,
                RoundedCorner.POSITION_BOTTOM_LEFT, RoundedCorner.POSITION_BOTTOM_RIGHT,
            )
            for (position in corners) {
                val corner = insets.getRoundedCorner(position) ?: continue
                val r = corner.radius
                if (r <= 0) continue
                // A rectangle's corner is fully visible once it is this far from
                // both screen edges: past the arc's 45° point, with a margin.
                val keep = ceil(r * (1 - 1 / sqrt(2.0)) * 1.2).toInt()
                val cx = corner.center.x
                val cy = corner.center.y
                val isLeft = position == RoundedCorner.POSITION_TOP_LEFT || position == RoundedCorner.POSITION_BOTTOM_LEFT
                val isTop = position == RoundedCorner.POSITION_TOP_LEFT || position == RoundedCorner.POSITION_TOP_RIGHT
                val edgeX = if (isLeft) cx - r + keep else cx + r - keep
                val edgeY = if (isTop) cy - r + keep else cy + r - keep
                val reachesX = if (isLeft) left < edgeX else right > edgeX
                val reachesY = if (isTop) top < edgeY else bottom > edgeY
                if (!reachesX || !reachesY) continue
                if (isLeft) out.left = max(out.left, edgeX - left) else out.right = max(out.right, right - edgeX)
                if (isTop) out.top = max(out.top, edgeY - top) else out.bottom = max(out.bottom, bottom - edgeY)
            }
        }

        if (Build.VERSION.SDK_INT >= 28) {
            insets.displayCutout?.let { cutout ->
                val root = view.rootView
                out.left = max(out.left, cutout.safeInsetLeft - left)
                out.top = max(out.top, cutout.safeInsetTop - top)
                out.right = max(out.right, cutout.safeInsetRight - (root.width - right))
                out.bottom = max(out.bottom, cutout.safeInsetBottom - (root.height - bottom))
            }
        }
    }
}
