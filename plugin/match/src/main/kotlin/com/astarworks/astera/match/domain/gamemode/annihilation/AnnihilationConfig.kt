package com.astarworks.astera.match.domain.gamemode.annihilation

/**
 * Configuration knobs for an Astera Annihilation match (ADR-0108,
 * Wave 5annihilation).
 *
 * Defaults match the Hypixel Annihilation cadence (5/10/sudden death) scaled
 * for Astera's smaller team count (3 teams vs 4). Configurable per-match via
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
 * @property pillarHp HP per pillar. Same for all tiers in Phase 1 PoC; Phase
 *   2 may add per-tier HP scaling (Tier 3 = the "real" Nexus, may justify
 *   higher HP). Default 500.
 * @property buildPhaseTicks Duration of [com.astarworks.astera.core.domain.nexus.NexusPhase.Phase1Build]
 *   in server ticks (20 ticks = 1 sec). Default 6000 = 5 min.
 * @property engagePhaseTicks Duration of [com.astarworks.astera.core.domain.nexus.NexusPhase.Phase2Engage]
 *   in server ticks. Default 12000 = 10 min.
 * @property tier3PhaseTicks Duration of [com.astarworks.astera.core.domain.nexus.NexusPhase.Phase3Tier3]
 *   before sudden-death triggers. Default 6000 = 5 min.
 * @property suddenDeathTicks Duration of [com.astarworks.astera.core.domain.nexus.NexusPhase.Phase4SuddenDeath]
 *   before forced HP-tiebreaker win. Default 1200 = 60 sec.
 * @property suddenDeathDotPerTick Sudden-death DoT applied to every surviving
 *   Tier 3 pillar per `IGameMode.onTick` invocation (mode default cadence:
 *   20 ticks). Default 5 HP per tick = 5 HP / sec → 300 HP / min.
 * @property respawnDelaySecondsTier1Lost Extra respawn delay applied to the
 *   owning team after their Tier 1 pillar falls. Default +2 sec.
 * @property resourceRegenMultTier2Lost Multiplier on the owning team's
 *   resource regen rate (Ammo / Stamina / Mana) after their Tier 2 pillar
 *   falls. Default 0.5 (= half regen). Wave 5k integration consumes this.
 */
public data class AnnihilationConfig(
    public val pillarCount: Int = 3,
    public val pillarHp: Int = 500,
    /** Damage one enemy block break deals to a pillar (the block itself never breaks). */
    public val pillarBreakDamage: Int = 25,
    public val buildPhaseTicks: Long = 6000L,
    public val engagePhaseTicks: Long = 12000L,
    public val tier3PhaseTicks: Long = 6000L,
    public val suddenDeathTicks: Long = 1200L,
    public val suddenDeathDotPerTick: Int = 5,
    public val respawnDelaySecondsTier1Lost: Int = 2,
    public val resourceRegenMultTier2Lost: Double = 0.5,
) {
    init {
        require(pillarCount == 3) {
            "AnnihilationConfig.pillarCount: only 3-pillar variant supported in Phase 1 PoC (got=$pillarCount)"
        }
        require(pillarBreakDamage > 0) { "AnnihilationConfig.pillarBreakDamage must be > 0 (got=$pillarBreakDamage)" }
        require(pillarHp > 0) { "AnnihilationConfig.pillarHp must be > 0 (got=$pillarHp)" }
        require(buildPhaseTicks >= 0) { "AnnihilationConfig.buildPhaseTicks must be >= 0 (got=$buildPhaseTicks)" }
        require(engagePhaseTicks > 0) { "AnnihilationConfig.engagePhaseTicks must be > 0 (got=$engagePhaseTicks)" }
        require(tier3PhaseTicks > 0) { "AnnihilationConfig.tier3PhaseTicks must be > 0 (got=$tier3PhaseTicks)" }
        require(suddenDeathTicks > 0) { "AnnihilationConfig.suddenDeathTicks must be > 0 (got=$suddenDeathTicks)" }
        require(suddenDeathDotPerTick > 0) {
            "AnnihilationConfig.suddenDeathDotPerTick must be > 0 (got=$suddenDeathDotPerTick)"
        }
        require(respawnDelaySecondsTier1Lost >= 0) {
            "AnnihilationConfig.respawnDelaySecondsTier1Lost must be >= 0 (got=$respawnDelaySecondsTier1Lost)"
        }
        require(resourceRegenMultTier2Lost in 0.0..1.0) {
            "AnnihilationConfig.resourceRegenMultTier2Lost must be in 0.0..1.0 (got=$resourceRegenMultTier2Lost)"
        }
    }

    public companion object {
        /** Phase 1 PoC defaults. Mirrors the values in `content/game-modes/annihilation.yaml`. */
        public val DEFAULT: AnnihilationConfig = AnnihilationConfig()
    }
}
