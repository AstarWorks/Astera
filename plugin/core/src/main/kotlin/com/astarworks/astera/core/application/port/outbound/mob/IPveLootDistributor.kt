package com.astarworks.astera.core.application.port.outbound.mob

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.MobKilled
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.mob.MobError

/**
 * Outbound port: resolves a mob's loot table and grants drops to the killing
 * player (Wave 5pve, ADR-0085).
 *
 * Subscribes to [MobKilled] (or is invoked directly by the mob lifecycle —
 * the wiring is plugin-side per the Phase 4 implementer's discretion) and
 * publishes [com.astarworks.astera.core.domain.event.PveLootDistributed]
 * after delivery.
 *
 * # Phase 1 vs Phase 4
 *
 * Wave 5pve commits the port shape; the default Phase 1 impl
 * ([com.astarworks.astera.core.application.service.mob.NoopPveLootDistributor])
 * always returns an empty drop list (no loot tables registered yet — the
 * `content/loot/` substrate is Phase 4 work). Phase 4 introduces
 * `ContentLootDistributor` that actually rolls a loot table.
 *
 * # Why not a use case
 *
 * The port shape (`(killEvent) → (granted items)`) is stable; the
 * resolution strategy (weighted roll, conditional table, party-shared
 * drops) varies. Modelling as a port lets Phase 4 swap impls without
 * touching call sites.
 */
public interface IPveLootDistributor {

    /**
     * Resolve [event].lootTable (via the killed mob's MobSpec) and grant
     * drops to [event].killerId. Skipped for null-killer kills (the impl
     * may still spawn world drops; this method is for player-attributed
     * grants).
     *
     * Returns the granted `(itemId, count)` list for downstream subscribers
     * (which is also published on [com.astarworks.astera.core.domain.event.PveLootDistributed]).
     */
    public fun distribute(event: MobKilled): Result<List<Pair<ItemId, Int>>, MobError>
}

