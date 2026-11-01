package com.astarworks.astera.match.domain.gamemode.annihilation

import com.astarworks.astera.core.domain.nexus.NexusPhase
import com.astarworks.astera.core.domain.nexus.NexusPillarTier

/**
 * Configuration knobs for an Astera Annihilation match (ADR-0108,
 * Wave 5annihilation).
 *
 * Defaults follow a Shotbow-style five-phase cadence (6/8/8/6 min + 5 min sudden death, ADR-0319). Configurable per-match via
 * `content/game-modes/annihilation.yaml`'s `default_config` map and parsed
 * into this domain type by [com.astarworks.astera.match.application.config.gamemode.annihilation.AnnihilationYamlOverlay].
 *
 * ## Why a domain config carrier (not a `Map<String, Any>`)?
 *
 * Two reasons:
 *
 *  1. The phase transition + scoring rules need to read these knobs on
 *     every tick. A typed carrier keeps the read site cheap and the wrong-
 *     type bug impossible.
 *  2. Tests want to construct degenerate configs (1-second build window,
 *     1 HP Nexus) without round-tripping through YAML. A `data class`
 *     constructor with defaults is the cleanest way to express that.
 *
 * The YAML wire shape stays a free-form `Map<String, String>` (mirrors the
 * Wave 5gamemode `GameModeYamlConfig.defaultConfig` convention). The
 * application-side overlay parses the map into this type once at boot;
 * downstream callers always work with the typed shape.
 *
 * @property pillarCount Number of pillars per team. Always 3 in Phase 1 PoC;
 *   exposed as a constructor parameter so a future YAML can opt into 2- or
 *   4-pillar variants without a breaking change.
 * @property pillarDamage How hand breaks, tool skills and weapons damage a pillar, and the distance falloff.
 * @property pillarHp HP of a Tier 1 pillar, and of every tier that has no
 *   [pillarHpTier2] / [pillarHpTier3] of its own. Default 500.
 * @property pillarHpTier2 HP of a Tier 2 pillar; null = [pillarHp].
 * @property pillarHpTier3 HP of a Tier 3 pillar (the Nexus proper); null = [pillarHp].
 *   A stage `pillars[].hp` still beats all three.
 * @property buildPhaseTicks Length of Phase1Build in server ticks (20 ticks = 1 sec). Default 7200 = 6 min.
 * @property engagePhaseTicks Length of Phase2Engage. Default 9600 = 8 min.
 * @property centerPhaseTicks Length of Phase3Center. Default 9600 = 8 min.
 * @property escalationPhaseTicks Length of Phase4Escalation. Default 7200 = 6 min.
 * @property suddenDeathTicks Length of Phase5SuddenDeath before the forced HP tiebreak. Default 6000 = 5 min.
 * @property escalationDamageMult Pillar damage multiplier in Phase4Escalation (default 1.5).
 * @property suddenDeathDamageMult Pillar damage multiplier in Phase5SuddenDeath (default 2.0).
 * @property bleedIntervalTicks Phase5 bleed cadence: every this many ticks each standing pillar loses [bleedPercent] of its max HP. Default 600 = 30 s.
 * @property bleedPercent Percent of a pillar's max HP lost per bleed (default 10).
 * @property bleedFloorPercent The bleed never takes a pillar below this percent of its max HP (default 10), so the bleed alone never destroys a pillar.
 * @property respawnDelaySecondsTier1Lost Extra respawn delay applied to the
 *   owning team after their Tier 1 pillar falls. Default +2 sec.
 * @property resourceRegenMultTier2Lost Multiplier on the owning team's
 *   resource regen rate (Ammo / Energy) after their Tier 2 pillar
 *   falls. Default 0.5 (= half regen). Wave 5k integration consumes this.
 */
public data class AnnihilationConfig(
    public val pillarCount: Int = 3,
    public val pillarHp: Int = 500,
    /** Pillar damage sources, multipliers and distance falloff (the pillar block itself never breaks). */
    public val pillarDamage: PillarDamageTuning = PillarDamageTuning(),
    public val buildPhaseTicks: Long = 7200L,
    public val engagePhaseTicks: Long = 9600L,
    public val centerPhaseTicks: Long = 9600L,
    public val escalationPhaseTicks: Long = 7200L,
    public val suddenDeathTicks: Long = 6000L,
    public val escalationDamageMult: Double = 1.5,
    public val suddenDeathDamageMult: Double = 2.0,
    public val bleedIntervalTicks: Long = 600L,
    public val bleedPercent: Int = 10,
    public val bleedFloorPercent: Int = 10,
    public val respawnDelaySecondsTier1Lost: Int = 2,
    public val resourceRegenMultTier2Lost: Double = 0.5,
    public val pillarHpTier2: Int? = null,
    public val pillarHpTier3: Int? = null,
) {
    init {
        require(pillarCount == 3) {
            "AnnihilationConfig.pillarCount: only 3-pillar variant supported in Phase 1 PoC (got=$pillarCount)"
        }
        require(pillarHp > 0) { "AnnihilationConfig.pillarHp must be > 0 (got=$pillarHp)" }
        require((pillarHpTier2 ?: 1) > 0) { "AnnihilationConfig.pillarHpTier2 must be > 0 (got=$pillarHpTier2)" }
        require((pillarHpTier3 ?: 1) > 0) { "AnnihilationConfig.pillarHpTier3 must be > 0 (got=$pillarHpTier3)" }
        require(buildPhaseTicks >= 0) { "AnnihilationConfig.buildPhaseTicks must be >= 0 (got=$buildPhaseTicks)" }
        require(engagePhaseTicks > 0) { "AnnihilationConfig.engagePhaseTicks must be > 0 (got=$engagePhaseTicks)" }
        require(centerPhaseTicks > 0) { "AnnihilationConfig.centerPhaseTicks must be > 0 (got=$centerPhaseTicks)" }
        require(escalationPhaseTicks > 0) { "AnnihilationConfig.escalationPhaseTicks must be > 0 (got=$escalationPhaseTicks)" }
        require(suddenDeathTicks > 0) { "AnnihilationConfig.suddenDeathTicks must be > 0 (got=$suddenDeathTicks)" }
        require(escalationDamageMult >= 1.0) { "AnnihilationConfig.escalationDamageMult must be >= 1 (got=$escalationDamageMult)" }
        require(suddenDeathDamageMult >= escalationDamageMult) {
            "AnnihilationConfig.suddenDeathDamageMult must be >= escalationDamageMult (got=$suddenDeathDamageMult)"
        }
        require(bleedIntervalTicks > 0) { "AnnihilationConfig.bleedIntervalTicks must be > 0 (got=$bleedIntervalTicks)" }
        require(bleedPercent in 1..100) { "AnnihilationConfig.bleedPercent must be in 1..100 (got=$bleedPercent)" }
        require(bleedFloorPercent in 0..99) { "AnnihilationConfig.bleedFloorPercent must be in 0..99 (got=$bleedFloorPercent)" }
        require(respawnDelaySecondsTier1Lost >= 0) {
            "AnnihilationConfig.respawnDelaySecondsTier1Lost must be >= 0 (got=$respawnDelaySecondsTier1Lost)"
        }
        require(resourceRegenMultTier2Lost in 0.0..1.0) {
            "AnnihilationConfig.resourceRegenMultTier2Lost must be in 0.0..1.0 (got=$resourceRegenMultTier2Lost)"
        }
    }

    /** Length in ticks of [phase]. */
    public fun ticksOf(phase: NexusPhase): Long = when (phase) {
        NexusPhase.Phase1Build -> buildPhaseTicks
        NexusPhase.Phase2Engage -> engagePhaseTicks
        NexusPhase.Phase3Center -> centerPhaseTicks
        NexusPhase.Phase4Escalation -> escalationPhaseTicks
        NexusPhase.Phase5SuddenDeath -> suddenDeathTicks
    }

    /** Multiplier on every point of pillar damage while [phase] runs. */
    public fun damageMultOf(phase: NexusPhase): Double = when (phase) {
        NexusPhase.Phase4Escalation -> escalationDamageMult
        NexusPhase.Phase5SuddenDeath -> suddenDeathDamageMult
        else -> 1.0
    }

    /** Starting HP of a pillar of [tier] (before any stage override). */
    public fun hpOf(tier: NexusPillarTier): Int = when (tier) {
        NexusPillarTier.TIER_1 -> pillarHp
        NexusPillarTier.TIER_2 -> pillarHpTier2 ?: pillarHp
        NexusPillarTier.TIER_3 -> pillarHpTier3 ?: pillarHp
    }

    public companion object {
        /** Phase 1 PoC defaults. Mirrors the values in `content/game-modes/annihilation.yaml`. */
        public val DEFAULT: AnnihilationConfig = AnnihilationConfig()
    }
}
