package com.astarworks.astera.core.domain.hud.status

/**
 * Geometry of pinning a glyph block to a fixed screen position inside the
 * action bar line (ADR-0182).
 *
 * The client centres the whole line: it starts at `-total / 2` from the
 * screen centre. With `readout (Wr) + gap (g) + block (Wh)` the total is
 * `Wr + g + Wh`, so the block's left edge is at `-(Wr + g + Wh) / 2 + Wr + g
 * = (Wr + g - Wh) / 2`. Solving for the edge `x` gives `g = 2x + Wh - Wr`.
 */
public object StatusLineAnchor {

    /** Pen advance to put between the readout and the block so the block's left edge lands on [anchorLeftPx]. */
    public fun gapPx(anchorLeftPx: Int, blockWidthPx: Int, readoutWidthPx: Int): Int =
        2 * anchorLeftPx + blockWidthPx - readoutWidthPx

    /** Where the block's left edge ends up (px from the screen centre; fractions of a pixel are real). */
    public fun blockLeftPx(readoutWidthPx: Int, gapPx: Int, blockWidthPx: Int): Double =
        (readoutWidthPx + gapPx - blockWidthPx) / 2.0
}
