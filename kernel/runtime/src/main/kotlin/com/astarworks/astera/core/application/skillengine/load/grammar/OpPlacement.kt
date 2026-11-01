package com.astarworks.astera.core.application.skillengine.load.grammar

import com.astarworks.astera.core.domain.skillengine.frame.grammar.Coeff
import com.astarworks.astera.core.domain.skillengine.math.Vector3d
import com.astarworks.astera.core.domain.skillengine.table.Val

/**
 * Where a positioned gameplay op acts (ADR-0314): either the legacy [anchor] (`anchor:`) or a point of a declared frame ([frame], `frame:` +
 * `pos: {v, w, s}`). Exactly one is set. The frame form reads the same per-tick frame table as every drawn op: its centre is a live
 * `frame:<id>#v,w,s` cell, and an oriented shape (box / line / wall, a hit sector, the summon heading) takes the frame's `v` axis as its
 * forward ([forward]) instead of the caster's heading.
 */
public class OpPlacement(public val anchor: AnchorRef?, public val frame: FrameRef?) {
    /** The live centre cell of a frame placement. */
    public fun centre(lc: com.astarworks.astera.core.application.skillengine.compose.LowerContext): Val.Live<Vector3d>? = frame?.baked(lc)?.position()

    /** The live forward cell of a frame placement: the frame's `v` axis (a direction, no origin). */
    public fun forward(): Val.Live<Vector3d>? = frame?.let { Val.Live(FrameRefs.directionCell(it.frame, Coeff(1.0, 0.0, 0.0)), null) }

    public companion object {
        /**
         * Reads the placement of an op that was written with the optional `anchor:` field ([anchor]) and/or `frame:` + `pos:` ([values]).
         * One of the two is required; both is a named load error.
         */
        public fun read(anchor: AnchorRef?, values: FieldValues, ctx: GrammarContext, path: String): OpPlacement {
            val frame = FrameRefs.read(values, ctx, path, anchorGiven = anchor != null)
            if (anchor == null && frame == null) ctx.fail(path, "needs a place: anchor: <id>, or frame: <id> with pos: {v, w, s} (ADR-0314)")
            return OpPlacement(anchor, frame)
        }
    }
}
