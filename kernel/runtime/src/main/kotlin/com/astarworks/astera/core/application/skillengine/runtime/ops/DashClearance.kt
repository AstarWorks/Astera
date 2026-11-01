package com.astarworks.astera.core.application.skillengine.runtime.ops

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.hit.HitBox

/**
 * Where a travelling caster body may stand relative to the hitboxes of other bodies. The caster is a vertical box of
 * [CASTER_HALF_WIDTH] half width and `height`; "clear" means that box overlaps no body box, so the caster's eye (the first-person
 * camera) is never inside another entity. Pure geometry, shared by every caster-moving op through [DashDriver].
 */
internal object DashClearance {
    /** Half width of a player-sized caster body. */
    const val CASTER_HALF_WIDTH: Double = 0.3

    /** How far in front of / behind a box face the resolved position rests. */
    const val GAP: Double = 0.02

    private const val EPS: Double = 1.0e-9

    /** The feet positions at which a caster of [height] overlaps [body]: the body box grown by the caster's width and height. */
    fun forbidden(body: HitBox, height: Double): HitBox = HitBox(
        Vec3(body.min.x - CASTER_HALF_WIDTH, body.min.y - height, body.min.z - CASTER_HALF_WIDTH),
        Vec3(body.max.x + CASTER_HALF_WIDTH, body.max.y, body.max.z + CASTER_HALF_WIDTH),
    )

    /** True when feet position [p] is strictly inside [box] (a position resting on a face is clear). */
    fun inside(box: HitBox, p: Vec3): Boolean =
        p.x > box.min.x + EPS && p.x < box.max.x - EPS &&
            p.y > box.min.y + EPS && p.y < box.max.y - EPS &&
            p.z > box.min.z + EPS && p.z < box.max.z - EPS

    /**
     * Slab test of the segment `from + t * delta`, t in [0, 1], against [box]: the (enter, exit) parameters of the part inside it,
     * or null when the segment misses it (or only grazes a face).
     */
    fun slab(box: HitBox, from: Vec3, delta: Vec3): Pair<Double, Double>? {
        var tMin = 0.0
        var tMax = 1.0
        val mins = doubleArrayOf(box.min.x, box.min.y, box.min.z)
        val maxs = doubleArrayOf(box.max.x, box.max.y, box.max.z)
        val o = doubleArrayOf(from.x, from.y, from.z)
        val d = doubleArrayOf(delta.x, delta.y, delta.z)
        for (i in 0..2) {
            if (kotlin.math.abs(d[i]) < EPS) {
                if (o[i] <= mins[i] + EPS || o[i] >= maxs[i] - EPS) return null
            } else {
                var t1 = (mins[i] - o[i]) / d[i]
                var t2 = (maxs[i] - o[i]) / d[i]
                if (t1 > t2) { val s = t1; t1 = t2; t2 = s }
                if (t1 > tMin) tMin = t1
                if (t2 < tMax) tMax = t2
                if (tMin >= tMax - EPS) return null
            }
        }
        return tMin to tMax
    }

    /**
     * The stop-at-the-front-edge end of a move [from] -> [to]: the point [GAP] before the first forbidden box the segment enters, or
     * null when no box is in the way. A move that starts inside a box cannot advance at all and answers [from].
     */
    fun frontEdge(forbidden: List<HitBox>, from: Vec3, to: Vec3): Vec3? {
        val delta = to - from
        val len = delta.length()
        if (len < EPS) return null
        var best: Double? = null
        for (box in forbidden) {
            if (inside(box, from)) return from
            val hit = slab(box, from, delta) ?: continue
            if (best == null || hit.first < best) best = hit.first
        }
        val t = best ?: return null
        val back = GAP / len
        return from + delta * (t - back).coerceAtLeast(0.0)
    }

    /**
     * The pass-through end: when [to] stands inside a forbidden box it moves on along [direction] to [GAP] past that box's far face, repeated
     * for boxes that touch the new spot. Null when the walk never leaves the boxes (cannot happen with finite boxes; the guard is the pass count).
     */
    fun behind(forbidden: List<HitBox>, to: Vec3, direction: Vec3): Vec3? {
        var p = to
        repeat(forbidden.size + 1) {
            val box = forbidden.firstOrNull { inside(it, p) } ?: return p
            val exit = slab(box, p, direction * 1.0e6) ?: return null
            p = p + direction * (exit.second * 1.0e6 + GAP)
        }
        return if (forbidden.any { inside(it, p) }) null else p
    }
}
