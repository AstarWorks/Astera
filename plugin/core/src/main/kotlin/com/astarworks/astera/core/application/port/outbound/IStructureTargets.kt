package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Damageable world structures (Annihilation pillars, WP10) as combat targets.
 *
 * Combat's targeting pipeline is keyed by [PlayerId]; a structure travels
 * through it under a stable synthetic id ([StructureTarget.id]), exactly like
 * a mob travels as `PlayerId(entityUuid)`. That way every damage source
 * (projectile, melee, AoE, `damage` mechanics) reaches a pillar through the
 * normal skill pipeline with no per-source special case.
 *
 * Lives in core because Match implements it and Combat consumes it (Combat
 * must not depend on Match; Match registers it in the shared registry after
 * Combat wires, so Combat resolves it lazily per call).
 *
 * Main thread only.
 */
public interface IStructureTargets {

    /** A live (not destroyed) structure that can currently be aimed at. */
    public data class StructureTarget(
        val id: PlayerId,
        /** Centre of the structure's block. */
        val position: Vec3,
        val world: WorldId,
    )

    /** Why [damage] changed nothing. */
    public enum class Rejection { UNKNOWN_TARGET, FRIENDLY, NOT_VULNERABLE, NO_ATTACKER }

    /** Standing structures whose centre is within [radius] of [center] (any world when [world] is null). */
    public fun findInRadius(center: Vec3, radius: Double, world: WorldId?): List<StructureTarget>

    /** True when [id] addresses a structure (standing or not) of a tracked match. */
    public fun isStructure(id: PlayerId): Boolean

    /** The standing structure occupying the block at [blockPosition], if any. */
    public fun targetAtBlock(world: WorldId?, blockPosition: Vec3): StructureTarget?

    /**
     * Applies [amount] (> 0) damage from [attacker]. On success returns the
     * structure's remaining HP (0.0 when this hit destroyed it).
     */
    public fun damage(target: PlayerId, attacker: PlayerId?, amount: Double): Result<Double, Rejection>

    /** Current HP, or null for an unknown id. */
    public fun hitPoints(id: PlayerId): Double?

    /** Maximum HP, or null for an unknown id. */
    public fun maxHitPoints(id: PlayerId): Double?
}
