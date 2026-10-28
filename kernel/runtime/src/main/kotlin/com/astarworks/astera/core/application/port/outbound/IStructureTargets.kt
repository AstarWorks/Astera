package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.ToolKind
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

    /** How a hit reached the structure; the structure's own rules weigh each kind differently. */
    public enum class DamageKind {
        /** A player broke the structure's block with their hands or a held tool. */
        HAND_BREAK,

        /** A tool skill (`break_area` with a `tool_class`) struck the structure's block. */
        TOOL_SKILL,

        /** Any other damage: weapon melee, weapon skill, projectile. */
        WEAPON,
    }

    /**
     * Where a hit came from. [tool] is the held tool for [DamageKind.HAND_BREAK] and the skill's tool class for
     * [DamageKind.TOOL_SKILL] (`NONE` = bare hand / no tool). [origin] is the attacker's position when the source
     * knows it; null lets the structure look the attacker up itself.
     */
    public data class DamageSource(
        val kind: DamageKind,
        val tool: ToolKind = ToolKind.NONE,
        val origin: Vec3? = null,
    )

    /** Why [damage] changed nothing. */
    public enum class Rejection { UNKNOWN_TARGET, FRIENDLY, NOT_VULNERABLE, NO_ATTACKER, OUT_OF_RANGE }

    /** Standing structures whose centre is within [radius] of [center] (any world when [world] is null). */
    public fun findInRadius(center: Vec3, radius: Double, world: WorldId?): List<StructureTarget>

    /** True when [id] addresses a structure (standing or not) of a tracked match. */
    public fun isStructure(id: PlayerId): Boolean

    /** The standing structure occupying the block at [blockPosition], if any. */
    public fun targetAtBlock(world: WorldId?, blockPosition: Vec3): StructureTarget?

    /**
     * Applies [amount] (> 0, the raw damage before the structure's own multipliers) from [attacker] through [source].
     * On success returns the structure's remaining HP (0.0 when this hit destroyed it).
     */
    public fun damage(target: PlayerId, attacker: PlayerId?, amount: Double, source: DamageSource): Result<Double, Rejection>

    /** Current HP, or null for an unknown id. */
    public fun hitPoints(id: PlayerId): Double?

    /** Maximum HP, or null for an unknown id. */
    public fun maxHitPoints(id: PlayerId): Double?
}
