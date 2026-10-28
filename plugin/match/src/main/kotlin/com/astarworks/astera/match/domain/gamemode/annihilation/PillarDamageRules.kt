package com.astarworks.astera.match.domain.gamemode.annihilation

import com.astarworks.astera.core.domain.world.ToolKind

/** How a hit reached a pillar (the domain mirror of the port's damage kind). */
public enum class PillarHitKind { HAND_BREAK, TOOL_SKILL, WEAPON }

/**
 * Pillar damage tunables (`default_config` of `content/game-modes/annihilation.yaml`).
 *
 * @property handBreakDamage Damage of one block break by hand (before [handMatchMult]).
 * @property handMatchMult Multiplier of a hand break while the held tool matches the pillar's affinity.
 * @property toolSkillMatchMult Multiplier of a tool skill (`break_area`) whose tool class matches the affinity.
 * @property toolSkillOtherMult Multiplier of a tool skill of another tool class.
 * @property weaponMult Multiplier of every other source (weapon melee, weapon skill, projectile).
 * @property falloffNear Distance (blocks) up to which a hit lands at full strength.
 * @property falloffFar Distance at which a hit has fallen to zero; linear in between.
 */
public data class PillarDamageTuning(
    val handBreakDamage: Int = 8,
    val handMatchMult: Double = 2.0,
    val toolSkillMatchMult: Double = 3.0,
    val toolSkillOtherMult: Double = 1.0,
    val weaponMult: Double = 0.18,
    val falloffNear: Double = 8.0,
    val falloffFar: Double = 32.0,
) {
    init {
        require(handBreakDamage > 0) { "PillarDamageTuning.handBreakDamage must be > 0 (got=$handBreakDamage)" }
        require(handMatchMult >= 0.0 && toolSkillMatchMult >= 0.0 && toolSkillOtherMult >= 0.0 && weaponMult >= 0.0) {
            "PillarDamageTuning multipliers must be >= 0"
        }
        require(falloffNear >= 0.0) { "PillarDamageTuning.falloffNear must be >= 0 (got=$falloffNear)" }
        require(falloffFar > falloffNear) {
            "PillarDamageTuning.falloffFar must be > falloffNear (got far=$falloffFar near=$falloffNear)"
        }
    }
}

/**
 * Pure pillar damage rule: raw damage x source multiplier x distance falloff.
 *
 *  - hand break: x [PillarDamageTuning.handMatchMult] when the held tool is the pillar's affinity, else x1;
 *  - tool skill: matching tool class x [PillarDamageTuning.toolSkillMatchMult], otherwise x [PillarDamageTuning.toolSkillOtherMult];
 *  - everything else: x [PillarDamageTuning.weaponMult];
 *  - then x [falloff] of the attacker's distance (1.0 within near, linear to 0.0 at far).
 */
public object PillarDamageRules {

    public fun falloff(tuning: PillarDamageTuning, distance: Double?): Double = when {
        distance == null || distance <= tuning.falloffNear -> 1.0
        distance >= tuning.falloffFar -> 0.0
        else -> (tuning.falloffFar - distance) / (tuning.falloffFar - tuning.falloffNear)
    }

    public fun sourceMultiplier(tuning: PillarDamageTuning, kind: PillarHitKind, tool: ToolKind, affinity: ToolKind): Double =
        when (kind) {
            PillarHitKind.HAND_BREAK -> if (tool == affinity) tuning.handMatchMult else 1.0
            PillarHitKind.TOOL_SKILL -> if (tool == affinity) tuning.toolSkillMatchMult else tuning.toolSkillOtherMult
            PillarHitKind.WEAPON -> tuning.weaponMult
        }

    /** Effective damage of a [raw] hit; 0.0 = nothing lands. [distance] null = unknown (full strength). */
    public fun effective(
        tuning: PillarDamageTuning,
        raw: Double,
        kind: PillarHitKind,
        tool: ToolKind,
        affinity: ToolKind,
        distance: Double?,
    ): Double = raw * sourceMultiplier(tuning, kind, tool, affinity) * falloff(tuning, distance)
}
