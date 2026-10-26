package com.astarworks.astera.core.application.service.mob.pve

import com.astarworks.astera.core.application.port.outbound.mob.IDeathPenaltyPolicy
import com.astarworks.astera.core.application.port.outbound.mob.IEncounterZoneRegistry
import com.astarworks.astera.core.domain.mob.encounter.DeathPenaltyRule
import com.astarworks.astera.core.domain.mob.encounter.EncounterZoneId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Phase 1 default impl of [IDeathPenaltyPolicy] (Wave 5pve-content, ADR-0099).
 *
 * Reads [DeathPenaltyRule] off the zone via [IEncounterZoneRegistry]. Falls
 * back to [DeathPenaltyRule.SAFE] for:
 *  - `null` zone id (deaths outside any encounter zone — PvP arena, lobby,
 *    creative-flag area)
 *  - unknown zone id (zone removed but a player was still inside — better
 *    safe death than dropping inventory on a phantom rule)
 *
 * # Why ignore [PlayerId] for now
 *
 * The port signature carries [PlayerId] so per-player overlays (VIP, opt-out,
 * tutorial protection) can land later without a signature change. Phase 1
 * has no such overlay so the default impl is zone-only. Overlay impls
 * register at priority 100 + delegate unknown-player decisions back to this
 * one.
 */
public class ZoneScopedDeathPenaltyPolicy(
    private val zoneRegistry: IEncounterZoneRegistry,
) : IDeathPenaltyPolicy {

    override fun ruleFor(player: PlayerId, zoneId: EncounterZoneId?): DeathPenaltyRule {
        if (zoneId == null) return DeathPenaltyRule.SAFE
        val zone = zoneRegistry.get(zoneId) ?: return DeathPenaltyRule.SAFE
        return zone.deathPenalty
    }
}
