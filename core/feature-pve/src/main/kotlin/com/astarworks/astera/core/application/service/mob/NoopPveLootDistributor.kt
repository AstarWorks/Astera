package com.astarworks.astera.core.application.service.mob

import com.astarworks.astera.core.application.port.outbound.mob.IPveLootDistributor
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.MobKilled
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.mob.MobError

/**
 * Phase 1 default impl of [IPveLootDistributor] (Wave 5pve, ADR-0085).
 *
 * Returns empty drops for every kill — no loot tables registered yet
 * (the `content/loot/` substrate is Phase 4 work). The Phase 4
 * `ContentLootDistributor` supersedes via priority-100 service registration
 * once the loot table substrate lands; this impl stays as the
 * always-available fallback so unit tests and headless boot keep working.
 */
public class NoopPveLootDistributor : IPveLootDistributor {
    override fun distribute(event: MobKilled): Result<List<Pair<ItemId, Int>>, MobError> =
        Result.success(emptyList())
}
