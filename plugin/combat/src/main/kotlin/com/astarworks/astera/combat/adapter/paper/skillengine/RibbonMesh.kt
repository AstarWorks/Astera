package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.core.domain.geometry.Vec3

/**
 * A trail ribbon built from the history of one point and a root point (ADR-0299). Each tick contributes a cross-section
 * (root, tip); consecutive cross-sections form one parallelogram [Segment]. Pure maths, no Bukkit.
 */
internal class RibbonMesh {

    private class Section(val root: Vec3, val tip: Vec3)

    /** One parallelogram: [centre], edge vectors [u] (travel) and [v] (root to tip), unit [normal]. */
    class Segment(val centre: Vec3, val u: Vec3, val v: Vec3, val normal: Vec3)

    private var last: Section? = null

    /** Adds this tick's cross-section; returns the segment from the previous one, or null (first point, or no movement). */
    fun push(root: Vec3, tip: Vec3): Segment? {
        val now = Section(root, tip)
        val before = last
        last = now
        if (before == null) return null
        val u = ((now.root - before.root) + (now.tip - before.tip)) * 0.5
        val v = ((before.tip - before.root) + (now.tip - now.root)) * 0.5
        val n = u.cross(v)
        if (u.lengthSquared() < 1e-10 || v.lengthSquared() < 1e-10 || n.lengthSquared() < 1e-14) return null
        val centre = (before.root + before.tip + now.root + now.tip) * 0.25
        return Segment(centre, u, v, n.normalize())
    }
}
