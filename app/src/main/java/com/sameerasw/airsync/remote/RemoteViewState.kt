package com.sameerasw.airsync.remote

/** A region of the display as fractions (SPACE.md §15.3 `view`): x, y top-left; w, h size. */
data class ViewRect(val x: Float, val y: Float, val w: Float, val h: Float) {
    companion object {
        val FULL = ViewRect(0f, 0f, 1f, 1f)
    }
}

/**
 * The Remote's zoomed view (design §5): pinch zooms 1× (whole display) … 6×; the view keeps the display's
 * shape (w = h = 1/zoom as fractions). While zoomed, the view follows the cursor so it stays inside a 20 %
 * margin; a three-finger pan turns following off until the next pinch-out to 1×. Pure, immutable.
 */
data class RemoteViewState(
    val zoom: Float = 1f,
    val x: Float = 0f,
    val y: Float = 0f,
    val follow: Boolean = true
) {
    val view: ViewRect get() = ViewRect(x, y, 1f / zoom, 1f / zoom)

    /**
     * Zooms by [factor] around a focus point given as fractions of the picture ([fx], [fy] in 0…1), keeping the
     * display point under the fingers in place. Reaching 1× turns following back on.
     */
    fun zoomBy(factor: Float, fx: Float, fy: Float): RemoteViewState {
        if (!factor.isFinite() || factor <= 0f) return this
        val nz = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val px = x + fx.coerceIn(0f, 1f) / zoom
        val py = y + fy.coerceIn(0f, 1f) / zoom
        val nx = px - fx.coerceIn(0f, 1f) / nz
        val ny = py - fy.coerceIn(0f, 1f) / nz
        val atFit = nz <= MIN_ZOOM + 0.001f
        return clamp(nz, nx, ny, if (atFit) true else follow)
    }

    /** Moves the view by a finger delta given as fractions of the picture (three-finger pan). Stops following. */
    fun panBy(dfx: Float, dfy: Float): RemoteViewState =
        if (zoom <= MIN_ZOOM + 0.001f) this
        else clamp(zoom, x - dfx / zoom, y - dfy / zoom, follow = false)

    /**
     * Keeps the cursor inside the view's inner area (a [MARGIN] of the view on every side), given the cursor as
     * fractions of the **current view** (as the frame header sends it). No change when not following or at 1×.
     */
    fun followCursor(cx: Float, cy: Float): RemoteViewState {
        if (!follow || zoom <= MIN_ZOOM + 0.001f || !cx.isFinite() || !cy.isFinite()) return this
        val w = 1f / zoom
        // Display fractions of the cursor.
        val dx = x + cx * w
        val dy = y + cy * w
        var nx = x
        var ny = y
        val m = MARGIN * w
        if (dx < nx + m) nx = dx - m
        if (dx > nx + w - m) nx = dx - w + m
        if (dy < ny + m) ny = dy - m
        if (dy > ny + w - m) ny = dy - w + m
        if (nx == x && ny == y) return this
        return clamp(zoom, nx, ny, follow)
    }

    /** Back to the whole display, following on. */
    fun reset(): RemoteViewState = RemoteViewState()

    private fun clamp(z: Float, nx: Float, ny: Float, follow: Boolean): RemoteViewState {
        val w = 1f / z
        return RemoteViewState(z, nx.coerceIn(0f, 1f - w), ny.coerceIn(0f, 1f - w), follow)
    }

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 6f
        const val MARGIN = 0.2f

        /** Is [a] different enough from [b] to tell the computer (avoids a message per tiny pinch step)? */
        fun differs(a: ViewRect, b: ViewRect): Boolean =
            kotlin.math.abs(a.x - b.x) > 0.002f || kotlin.math.abs(a.y - b.y) > 0.002f ||
                kotlin.math.abs(a.w - b.w) > 0.002f || kotlin.math.abs(a.h - b.h) > 0.002f
    }
}
