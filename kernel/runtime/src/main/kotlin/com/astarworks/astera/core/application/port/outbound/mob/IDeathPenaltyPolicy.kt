package com.astarworks.astera.core.application.port.outbound.mob

import com.astarworks.astera.core.domain.mob.encounter.DeathPenaltyRule
import com.astarworks.astera.core.domain.mob.encounter.EncounterZoneId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: resolves the [DeathPenaltyRule] for a player death in a
 * specific encounter zone (Wave 5pve-content, ADR-0099).
 *
 * Phase 1 PoC scope:
 *   - The Phase 1 default impl (`ZoneScopedDeathPenaltyPolicy`) reads the
 *     [DeathPenaltyRule] off the zone's [EncounterZoneId] via
 *     [IEncounterZoneRegistry]. Falls back to [DeathPenaltyRule.SAFE] if the
 *     zone is unknown.
 *   - Wave 6b's Paper death-handler service subscribes to player deaths,
 *     consults this policy to decide drop vs no-drop, and applies the
 *     grace-tick reservation on dropped items.
 *
 * # Why a separate port (vs reading `EncounterZone.deathPenalty` directly)
 *
 * Two reasons:
 *  1. Per-player overrides land naturally as a priority-100 overlay impl
 *     (e.g. "VIP players take safe-death penalty regardless of zone") without
 *     touching call sites.
 *  2. Phase 4 will likely fold in PvP-flagging — outside-zone deaths in
 *     contested area can apply zone-equivalent penalty. Keeping the
 *     resolution behind a port lets that logic land in one place.
 *
 * # Why this is read-only (no `apply()` mutation method)
 *
 * The policy resolves the **decision**; applying the decision (dropping
 * items, awarding death credit, respawning at hub) is the responsibility of
 * the Wave 6b Paper death handler. Keeping policy and effect separate makes
 * the policy a pure function of player + zone, which is trivial to test.
 */
public interface IDeathPenaltyPolicy {

    /**
     * Resolve the death penalty for [player] dying inside [zoneId].
     *
     * @param player Player who died. Carried so per-player overlays can
     *   inspect VIP status / opt-out flags / debug overrides.
     * @param zoneId Zone the death occurred in, or `null` for deaths
     *   outside any registered zone (the Phase 1 impl returns
     *   [DeathPenaltyRule.SAFE] for those).
     * @return The [DeathPenaltyRule] the Wave 6b death handler should apply.
     */
    public fun ruleFor(player: PlayerId, zoneId: EncounterZoneId?): DeathPenaltyRule
}
