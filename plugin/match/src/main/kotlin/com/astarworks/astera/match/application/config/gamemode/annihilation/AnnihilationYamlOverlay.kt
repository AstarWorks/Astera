package com.astarworks.astera.match.application.config.gamemode.annihilation

import com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationConfig
import com.astarworks.astera.match.domain.gamemode.annihilation.PillarDamageTuning

/**
 * Parses the free-form `default_config: Map<String, String>` from
 * `content/game-modes/annihilation.yaml` into a typed
 * [AnnihilationConfig] (ADR-0108, Wave 5annihilation).
 *
 * The Wave 5gamemode YAML schema (`GameModeYamlConfig`) keeps
 * `default_config` as a free-form map for Phase 1 PoC — typed per-mode
 * carriers land in Phase 3 once two non-Norma modes inform the cross-mode
 * shape. This overlay is the per-mode adapter that turns the map into
 * [AnnihilationConfig] without forcing the framework to know about
 * Annihilation-specific keys.
 *
 * ## Schema (all optional)
 *
 * ```yaml
 * default_config:
 *   pillar_hp: 500                           # int, Tier 1 (and any tier without its own key)
 *   pillar_hp_tier2: 500                     # int, optional; default = pillar_hp
 *   pillar_hp_tier3: 500                     # int, optional; default = pillar_hp
 *   pillar_break_damage: 8                   # int, base damage of one enemy block break by hand on a pillar
 *   pillar_hand_match_mult: 2.0              # double, hand break with the pillar's affinity tool in hand
 *   pillar_tool_skill_match_mult: 3.0        # double, tool skill (break_area) of the affinity tool class
 *   pillar_tool_skill_other_mult: 1.0        # double, tool skill of another tool class
 *   pillar_weapon_mult: 0.18                 # double, weapon melee / skill / projectile
 *   pillar_falloff_near: 8.0                 # double, blocks: full damage up to here
 *   pillar_falloff_far: 32.0                 # double, blocks: zero damage from here (linear in between)
 *   build_phase_ticks: 7200                  # long (= 6 min @ 20 tps), phase 1
 *   engage_phase_ticks: 9600                 # long, phase 2
 *   center_phase_ticks: 9600                 # long, phase 3
 *   escalation_phase_ticks: 7200             # long, phase 4
 *   sudden_death_ticks: 6000                 # long, phase 5 (then the HP tiebreak ends the match)
 *   escalation_damage_mult: 1.5              # double >= 1, pillar damage in phase 4
 *   sudden_death_damage_mult: 2.0            # double >= escalation_damage_mult, pillar damage in phase 5
 *   bleed_interval_ticks: 600                # long, phase 5: every interval each pillar bleeds
 *   bleed_percent: 10                        # int 1..100, percent of max HP lost per bleed
 *   bleed_floor_percent: 10                  # int 0..99, the bleed never goes below this percent of max HP
 *   respawn_delay_seconds_tier1_lost: 2      # int
 *   resource_regen_mult_tier2_lost: 0.5      # double 0.0..1.0
 * ```
 *
 * `pillar_count` is **not** YAML-overridable in Phase 1 — only 3-pillar
 * variant is supported (enforced in `AnnihilationConfig.init`).
 *
 * Unknown keys are ignored (the map is shared with the Norma layer and the mode framework), but a known key
 * with an unparseable or out-of-range value fails loudly with an [IllegalArgumentException] (ADR-0319).
 */
public object AnnihilationYamlOverlay {

    /** Resolve [defaults] augmented with overrides from [raw]. Throws [IllegalArgumentException] on a bad value. */
    public fun parse(
        raw: Map<String, String>,
        defaults: AnnihilationConfig = AnnihilationConfig.DEFAULT,
    ): Result {
        val warnings = mutableListOf<String>()
        for (removed in REMOVED_KEYS) {
            require(removed !in raw) { "annihilation.yaml: '$removed' was removed by ADR-0319 (see the phase keys in this file's header)" }
        }

        val pillarHp = parseInt(raw, "pillar_hp", defaults.pillarHp, warnings)
        val hpTier2 = parseInt(raw, "pillar_hp_tier2", defaults.pillarHpTier2 ?: pillarHp, warnings)
        val hpTier3 = parseInt(raw, "pillar_hp_tier3", defaults.pillarHpTier3 ?: pillarHp, warnings)
        val d = defaults.pillarDamage
        val damage = run {
            PillarDamageTuning(
                handBreakDamage = parseInt(raw, "pillar_break_damage", d.handBreakDamage, warnings),
                handMatchMult = parseDouble(raw, "pillar_hand_match_mult", d.handMatchMult, warnings),
                toolSkillMatchMult = parseDouble(raw, "pillar_tool_skill_match_mult", d.toolSkillMatchMult, warnings),
                toolSkillOtherMult = parseDouble(raw, "pillar_tool_skill_other_mult", d.toolSkillOtherMult, warnings),
                weaponMult = parseDouble(raw, "pillar_weapon_mult", d.weaponMult, warnings),
                falloffNear = parseDouble(raw, "pillar_falloff_near", d.falloffNear, warnings),
                falloffFar = parseDouble(raw, "pillar_falloff_far", d.falloffFar, warnings),
            )
        }
        val buildTicks = parseLong(raw, "build_phase_ticks", defaults.buildPhaseTicks, warnings)
        val engageTicks = parseLong(raw, "engage_phase_ticks", defaults.engagePhaseTicks, warnings)
        val centerTicks = parseLong(raw, "center_phase_ticks", defaults.centerPhaseTicks, warnings)
        val escalationTicks = parseLong(raw, "escalation_phase_ticks", defaults.escalationPhaseTicks, warnings)
        val suddenTicks = parseLong(raw, "sudden_death_ticks", defaults.suddenDeathTicks, warnings)
        val escalationMult = parseDouble(raw, "escalation_damage_mult", defaults.escalationDamageMult, warnings)
        val suddenMult = parseDouble(raw, "sudden_death_damage_mult", defaults.suddenDeathDamageMult, warnings)
        val bleedInterval = parseLong(raw, "bleed_interval_ticks", defaults.bleedIntervalTicks, warnings)
        val bleedPercent = parseInt(raw, "bleed_percent", defaults.bleedPercent, warnings)
        val bleedFloor = parseInt(raw, "bleed_floor_percent", defaults.bleedFloorPercent, warnings)
        val respawnDelay = parseInt(
            raw, "respawn_delay_seconds_tier1_lost", defaults.respawnDelaySecondsTier1Lost, warnings,
        )
        val regenMult = parseDouble(
            raw, "resource_regen_mult_tier2_lost", defaults.resourceRegenMultTier2Lost, warnings,
        )

        val config = run {
            AnnihilationConfig(
                pillarCount = defaults.pillarCount,
                pillarHp = pillarHp,
                pillarHpTier2 = hpTier2,
                pillarHpTier3 = hpTier3,
                pillarDamage = damage,
                buildPhaseTicks = buildTicks,
                engagePhaseTicks = engageTicks,
                centerPhaseTicks = centerTicks,
                escalationPhaseTicks = escalationTicks,
                suddenDeathTicks = suddenTicks,
                escalationDamageMult = escalationMult,
                suddenDeathDamageMult = suddenMult,
                bleedIntervalTicks = bleedInterval,
                bleedPercent = bleedPercent,
                bleedFloorPercent = bleedFloor,
                respawnDelaySecondsTier1Lost = respawnDelay,
                resourceRegenMultTier2Lost = regenMult,
            )
        }
        return Result(config, warnings)
    }

    private val REMOVED_KEYS = listOf("tier3_phase_ticks", "sudden_death_dot_per_tick")

    public data class Result(val config: AnnihilationConfig, val warnings: List<String>)

    private fun parseInt(raw: Map<String, String>, key: String, default: Int, warnings: MutableList<String>): Int {
        val v = raw[key] ?: return default
        return v.toIntOrNull() ?: throw IllegalArgumentException("annihilation.yaml: '$key' is not an Int (got '$v')")
    }

    private fun parseLong(raw: Map<String, String>, key: String, default: Long, warnings: MutableList<String>): Long {
        val v = raw[key] ?: return default
        return v.toLongOrNull() ?: throw IllegalArgumentException("annihilation.yaml: '$key' is not a Long (got '$v')")
    }

    private fun parseDouble(raw: Map<String, String>, key: String, default: Double, warnings: MutableList<String>): Double {
        val v = raw[key] ?: return default
        return v.toDoubleOrNull() ?: throw IllegalArgumentException("annihilation.yaml: '$key' is not a Double (got '$v')")
    }
}
