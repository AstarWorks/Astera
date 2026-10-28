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
 *   build_phase_ticks: 6000                  # long (= 5 min @ 20 tps)
 *   engage_phase_ticks: 12000                # long
 *   tier3_phase_ticks: 6000                  # long
 *   sudden_death_ticks: 1200                 # long (= 60 sec)
 *   sudden_death_dot_per_tick: 5             # int
 *   respawn_delay_seconds_tier1_lost: 2      # int
 *   resource_regen_mult_tier2_lost: 0.5      # double 0.0..1.0
 * ```
 *
 * `pillar_count` is **not** YAML-overridable in Phase 1 — only 3-pillar
 * variant is supported (enforced in `AnnihilationConfig.init`).
 *
 * Unknown keys are silently ignored — same as the `GameModeYamlConfig`
 * convention. Unparseable values fall back to the default for that field
 * and surface a warning via the supplied callback.
 */
public object AnnihilationYamlOverlay {

    /**
     * Resolve [defaults] augmented with overrides from [raw]. Returns the
     * typed config + a list of warning messages for unparseable values.
     *
     * Implementation note: parse failures are *soft* — we don't fail the
     * whole `GameModeContents.bootstrap` call because of a malformed
     * `engage_phase_ticks: "twelve thousand"`. The default is kept and the
     * warning is logged so the operator notices.
     */
    public fun parse(
        raw: Map<String, String>,
        defaults: AnnihilationConfig = AnnihilationConfig.DEFAULT,
    ): Result {
        val warnings = mutableListOf<String>()

        val pillarHp = parseInt(raw, "pillar_hp", defaults.pillarHp, warnings)
        val hpTier2 = parseInt(raw, "pillar_hp_tier2", defaults.pillarHpTier2 ?: pillarHp, warnings)
        val hpTier3 = parseInt(raw, "pillar_hp_tier3", defaults.pillarHpTier3 ?: pillarHp, warnings)
        val d = defaults.pillarDamage
        val damage = try {
            PillarDamageTuning(
                handBreakDamage = parseInt(raw, "pillar_break_damage", d.handBreakDamage, warnings),
                handMatchMult = parseDouble(raw, "pillar_hand_match_mult", d.handMatchMult, warnings),
                toolSkillMatchMult = parseDouble(raw, "pillar_tool_skill_match_mult", d.toolSkillMatchMult, warnings),
                toolSkillOtherMult = parseDouble(raw, "pillar_tool_skill_other_mult", d.toolSkillOtherMult, warnings),
                weaponMult = parseDouble(raw, "pillar_weapon_mult", d.weaponMult, warnings),
                falloffNear = parseDouble(raw, "pillar_falloff_near", d.falloffNear, warnings),
                falloffFar = parseDouble(raw, "pillar_falloff_far", d.falloffFar, warnings),
            )
        } catch (e: IllegalArgumentException) {
            warnings += "annihilation.yaml: pillar damage tunables rejected (${e.message}); using defaults."
            d
        }
        val buildTicks = parseLong(raw, "build_phase_ticks", defaults.buildPhaseTicks, warnings)
        val engageTicks = parseLong(raw, "engage_phase_ticks", defaults.engagePhaseTicks, warnings)
        val tier3Ticks = parseLong(raw, "tier3_phase_ticks", defaults.tier3PhaseTicks, warnings)
        val suddenTicks = parseLong(raw, "sudden_death_ticks", defaults.suddenDeathTicks, warnings)
        val suddenDot = parseInt(raw, "sudden_death_dot_per_tick", defaults.suddenDeathDotPerTick, warnings)
        val respawnDelay = parseInt(
            raw, "respawn_delay_seconds_tier1_lost", defaults.respawnDelaySecondsTier1Lost, warnings,
        )
        val regenMult = parseDouble(
            raw, "resource_regen_mult_tier2_lost", defaults.resourceRegenMultTier2Lost, warnings,
        )

        val config = try {
            AnnihilationConfig(
                pillarCount = defaults.pillarCount,
                pillarHp = pillarHp,
                pillarHpTier2 = hpTier2,
                pillarHpTier3 = hpTier3,
                pillarDamage = damage,
                buildPhaseTicks = buildTicks,
                engagePhaseTicks = engageTicks,
                tier3PhaseTicks = tier3Ticks,
                suddenDeathTicks = suddenTicks,
                suddenDeathDotPerTick = suddenDot,
                respawnDelaySecondsTier1Lost = respawnDelay,
                resourceRegenMultTier2Lost = regenMult,
            )
        } catch (e: IllegalArgumentException) {
            warnings += "annihilation.yaml: config rejected by domain validation (${e.message}); using defaults."
            defaults
        }
        return Result(config, warnings)
    }

    public data class Result(val config: AnnihilationConfig, val warnings: List<String>)

    private fun parseInt(raw: Map<String, String>, key: String, default: Int, warnings: MutableList<String>): Int {
        val v = raw[key] ?: return default
        return v.toIntOrNull() ?: run {
            warnings += "annihilation.yaml: '$key' is not an Int (got '$v'); using default $default."
            default
        }
    }

    private fun parseLong(raw: Map<String, String>, key: String, default: Long, warnings: MutableList<String>): Long {
        val v = raw[key] ?: return default
        return v.toLongOrNull() ?: run {
            warnings += "annihilation.yaml: '$key' is not a Long (got '$v'); using default $default."
            default
        }
    }

    private fun parseDouble(raw: Map<String, String>, key: String, default: Double, warnings: MutableList<String>): Double {
        val v = raw[key] ?: return default
        return v.toDoubleOrNull() ?: run {
            warnings += "annihilation.yaml: '$key' is not a Double (got '$v'); using default $default."
            default
        }
    }
}
