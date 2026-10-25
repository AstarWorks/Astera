package com.astarworks.astera.core.application.service.mob

import com.astarworks.astera.core.application.port.outbound.mob.IPveXpDistributor
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.MobKilled
import com.astarworks.astera.core.domain.mob.MobError

/**
 * Phase 1 default impl of [IPveXpDistributor] (Wave 5pve, ADR-0085).
 *
 * Returns `0` XP for every kill — XP curve + per-mob yield is Phase 4
 * balance work. Phase 4's `BalancedPveXpDistributor` supersedes via
 * priority-100 service registration.
 */
public class NoopPveXpDistributor : IPveXpDistributor {
    override fun distribute(event: MobKilled): Result<Int, MobError> = Result.success(0)
}
