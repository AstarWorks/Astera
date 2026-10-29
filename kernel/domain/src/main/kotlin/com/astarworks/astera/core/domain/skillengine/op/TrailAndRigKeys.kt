package com.astarworks.astera.core.domain.skillengine.op

import com.astarworks.astera.core.domain.geometry.Vec3

/**
 * One radial band of a a trail: colour `#rrggbb`, peak [alpha] 0..1, optional [colorTo] it has faded to at the end of the life.
 * [block] is the crisp slab block of the styles `crisp` / `both` (a block display that shrinks as it fades); a `crisp` band without one draws nothing.
 */
public data class TrailBand(val from: Double, val to: Double, val color: String, val alpha: Double, val colorTo: String? = null, val block: String? = null)

/** Leading edge of a a trail: a [block] slab line [width] blocks wide centred on the tip path, drawn for every style; it thins out as it ages. */
public data class TrailEdge(val block: String, val width: Double)

/** Dark rim outside the tip of a a trail: `#rrggbb` at [alpha], [width] blocks wide. */
public data class TrailRim(val color: String, val alpha: Double, val width: Double)

/** One pose key of a rig: absolute rotation-vector [rotate] (null keeps the previous), [ease] of the segment ENDING here (`step` = cut). */
public data class RigSwingKey(val tick: Int, val rotate: Vec3?, val ease: String?)
