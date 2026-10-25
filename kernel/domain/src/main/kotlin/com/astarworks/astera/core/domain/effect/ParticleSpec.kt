package com.astarworks.astera.core.domain.effect

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Vendor-neutral particle emission descriptor.
 *
 * `count > 0`: spawn [count] particles scattered by [offset] (per-axis spread), speed
 * [extra]. `count == 0`: vanilla's "directional" form, a single particle whose velocity
 * is [offset] scaled by [extra] (flames shooting outwards). [color] / [scale] select
 * `Particle.DUST` options (ADR-0190).
 */
public data class ParticleSpec(
    val key: String,
    val count: Int,
    val offset: Vec3 = Vec3.ZERO,
    /** Vanilla `extra` argument: speed for velocity particles (and for `count == 0` direction spawns). */
    val extra: Double = 0.0,
    /** RGB tint for `dust` (and similar colour-carrying) particles; `null` = the particle's own colour. */
    val color: ParticleColor? = null,
    /** Dust size (vanilla range 0.01..4). Ignored when [color] is null. */
    val scale: Float = 1.0f,
    /** Typed data for non-dust particles (block / item / trail / ...); wins over [color] / [scale] when set. */
    val data: ParticleData? = null,
    /** `World.spawnParticle(..., force)`: render beyond the normal 32-block particle range. */
    val force: Boolean = false,
    val viewers: ParticleViewers = ParticleViewers.ALL,
    /** The caster, needed to resolve [viewers] other than `ALL`. */
    val source: PlayerId? = null,
) {
    init {
        require(key.isNotBlank()) { "particle key must not be blank" }
        require(count >= 0) { "count must be >= 0, got $count" }
        require(scale > 0f) { "scale must be > 0, got $scale" }
    }
}
