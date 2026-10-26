package com.astarworks.astera.core.application.port.outbound.mob

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.MobKilled
import com.astarworks.astera.core.domain.mob.MobError

/**
 * Outbound port: converts a mob kill into player XP (Wave 5pve, ADR-0085).
 *
 * Pairs with [IPveLootDistributor] — together they form the post-mob-kill
 * reward pipeline. Subscribes to [MobKilled] and publishes
 * [com.astarworks.astera.core.domain.event.PveXpGranted] for player-credited
 * kills (skipped for null-killer environmental kills).
 *
 * # Phase 1 vs Phase 4
 *
 * Wave 5pve commits the port; the default impl
 * ([com.astarworks.astera.core.application.service.mob.NoopPveXpDistributor])
 * returns 0 XP for every kill (the XP curve + per-mob yield table is Phase
 * 4 balance work). Phase 4's `BalancedPveXpDistributor` consults a YAML
 * yield table and applies player-level scaling.
 *
 * # Integration with existing Norma category
 *
 * Per ADR-0085 §"Norma category extension", Phase 4 wires this distributor
 * to publish `NormaProgressed` for the new "PvE kill" category alongside
 * `PveXpGranted`. The split lets observers care about *just* XP or *just*
 * norma without coupling.
 */
public interface IPveXpDistributor {

    /**
     * Compute and grant XP for the given [event]. Returns the granted XP
     * amount (`0` valid — e.g. a balance flag may zero out the grant
     * without skipping the event so downstream audit still records the
     * kill). Null-killer events return `0` without publishing.
     */
    public fun distribute(event: MobKilled): Result<Int, MobError>
}

