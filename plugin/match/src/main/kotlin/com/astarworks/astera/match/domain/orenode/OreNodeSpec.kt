package com.astarworks.astera.match.domain.orenode

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.ToolKind
import com.astarworks.astera.core.domain.world.ToolTier
import com.astarworks.astera.core.domain.world.ToolType
import kotlin.math.ceil

/** One item stack a mined node gives. */
public data class OreDrop(val itemId: String, val count: Int)

/**
 * A group of identical Mid ore nodes of one stage (`content/ore-nodes/<stage-id>.yaml`): the vanilla [material] they show,
 * the [placeholder] material standing in for [regrowSeconds] after a mine, the minimum tool tier, the stack they give,
 * and where they stand ([cells], stage-relative x/z; y is absolute).
 *
 * A node has [maxHp] (the block's `base_durability`). Every hit loses HP by the node block's `tool_effectiveness` entry of the
 * held tool ([hitDamage]); the matching tool class (the affinity) has big entries, the others small ones, so the right tool
 * is fast and the wrong one slow. A player under [minNormaLevel] deals nothing; above it [levelSpeed] speeds the hit up.
 * A tool skill hits [skillMult] times a hand hit (scaled by the skill's `block_damage` / 100).
 */
public data class OreNodeSpec(
    val material: String,
    val placeholder: String,
    val regrowSeconds: Int,
    /** Minimum tier of whatever tool is used (any class). */
    val requiredTier: ToolTier,
    val drops: List<OreDrop>,
    val cells: List<Vec3>,
    val maxHp: Int,
    /** HP one hand hit removes per tool class and tier; a tier without an entry uses the best lower tier of its class, a class without one deals nothing. */
    val hitDamage: Map<ToolType, Int>,
    /** Lowest Norma level that may damage the node at all. */
    val minNormaLevel: Int,
    /** Break-speed multiplier per Norma level: the entry of the highest level at or below the player's. Always has level 1. */
    val levelSpeed: Map<Int, Double>,
    /** Multiplier of a tool-skill strike relative to a hand hit. */
    val skillMult: Double,
)

/** Pure damage arithmetic of an ore node. */
public object OreNodeRules {

    /** HP a hand hit with [kind]/[tier] removes before the level multiplier; 0 = this class cannot hurt the node. */
    public fun baseDamage(spec: OreNodeSpec, kind: ToolKind, tier: ToolTier): Int =
        spec.hitDamage.filterKeys { it.kind == kind && it.tier <= tier }.maxByOrNull { it.key.tier }?.value ?: 0

    /** The break-speed multiplier of Norma [level]. */
    public fun levelMult(spec: OreNodeSpec, level: Int): Double =
        spec.levelSpeed.filterKeys { it <= level }.maxByOrNull { it.key }?.value ?: spec.levelSpeed.getValue(1)

    /**
     * HP removed by one strike (0 = nothing lands). A hand hit is [baseDamage] x level; a skill strike (`blockDamage` != null)
     * is that x [OreNodeSpec.skillMult] x `blockDamage / 100`. A strike that lands removes at least 1 HP.
     */
    public fun damage(spec: OreNodeSpec, kind: ToolKind, tier: ToolTier, level: Int, blockDamage: Int? = null): Int {
        val base = baseDamage(spec, kind, tier)
        if (base <= 0) return 0
        val skill = blockDamage?.let { spec.skillMult * it / SKILL_REFERENCE_DAMAGE } ?: 1.0
        return ceil(base * levelMult(spec, level) * skill).toInt().coerceAtLeast(1)
    }

    /** The `block_damage` of a plain tool skill; a skill with more hits the node harder in proportion. */
    private const val SKILL_REFERENCE_DAMAGE = 100.0
}
