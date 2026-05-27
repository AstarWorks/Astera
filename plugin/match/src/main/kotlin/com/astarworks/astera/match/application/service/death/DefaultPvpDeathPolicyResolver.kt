package com.astarworks.astera.match.application.service.death

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.application.port.outbound.IMatchModeRegistry
import com.astarworks.astera.match.application.port.outbound.IPvpDeathPolicyResolver
import com.astarworks.astera.match.domain.death.PvpDeathRule

/**
 * Default [IPvpDeathPolicyResolver] (Wave 5game-complete, ADR-0121 §E).
 *
 * Resolution chain (highest priority first):
 *
 *  1. **Stage YAML override** — `stageOverrides[stageId]` map populated
 *     by `AsteraMatchPlugin` when loading `content/stages/<stage>.yaml` that
 *     contain a `pvp_death:` block. The map is mutable so the reload
 *     dispatcher can refresh entries without rebuilding the resolver.
 *  2. **Mode default** — `modeRegistry.modeKeyOf(matchId)` resolved via
 *     [defaultRuleForMode].
 *  3. **Final fallback** — [PvpDeathRule.KeepInventory] (safest: never
 *     destroys items when in doubt).
 *
 * Phase 1 PoC ships **no stage YAML pvp_death blocks** — the override
 * map starts empty. Authors land the YAML schema additions in Wave
 * 5persist or whichever wave next touches `content/stages/`. The shape
 * is reserved here so the listener wiring + tests don't have to change
 * later.
 *
 * # Why a class, not an object
 *
 * The class takes [matchRepo] + [modeRegistry] + a per-stage overlay
 * map so the resolver can be wired with priority via `ServiceRegistry`
 * and replaced by a Persistence-backed variant once stage overrides
 * persist across reloads.
 */
public class DefaultPvpDeathPolicyResolver(
    private val matchRepo: IMatchRepository,
    private val modeRegistry: IMatchModeRegistry,
    private val stageOverrides: MutableMap<String, PvpDeathRule> = mutableMapOf(),
) : IPvpDeathPolicyResolver {

    override fun resolveForMatch(matchId: MatchId): PvpDeathRule {
        val match = matchRepo.findById(matchId)
        if (match != null) {
            val override = stageOverrides[match.stageId.value]
            if (override != null) return override
        }
        val modeKey = modeRegistry.modeKeyOf(matchId)
        return defaultRuleForMode(modeKey)
    }

    /**
     * Register or update a per-stage override. `null` unregisters.
     * Called by `AsteraMatchPlugin` once stage YAML loading lands a
     * `pvp_death:` block.
     */
    public fun setStageOverride(stageId: String, rule: PvpDeathRule?) {
        if (rule == null) stageOverrides.remove(stageId) else stageOverrides[stageId] = rule
    }

    public companion object {

        /**
         * Per-mode default. Public so callers (tests, Phase 2 override
         * authoring tools) can sanity-check a mode's baseline without
         * spinning up the full resolver.
         */
        public fun defaultRuleForMode(modeKey: String?): PvpDeathRule = when (modeKey) {
            MODE_KEY_ANNIHILATION -> PvpDeathRule.DropEquipped
            MODE_KEY_BET_1V1 -> PvpDeathRule.DropAll
            // Norma + null + unknown all fall to safe default.
            else -> PvpDeathRule.KeepInventory
        }

        public const val MODE_KEY_ANNIHILATION: String = "annihilation"
        public const val MODE_KEY_BET_1V1: String = "bet-1v1"
    }
}
