package com.astarworks.astera.combat.application.port.outbound

import com.astarworks.astera.combat.domain.resource.ManaError
import com.astarworks.astera.combat.domain.resource.ManaPool
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound: per-player mana pool management.
 *
 * Default Wave 5a impl is `InMemoryManaPoolService` (worker ε). Wave 5e adds
 * persistence (carryover between matches). Wave 5a `ConsumeManaUseCase` calls
 * [consume] before publishing `SkillCast`.
 */
public interface IManaPoolService {
    public fun get(playerId: PlayerId): ManaPool?

    public fun consume(playerId: PlayerId, amount: Long): Result<ManaPool, ManaError>

    public fun regen(playerId: PlayerId, ticksElapsed: Long): ManaPool

    public fun init(playerId: PlayerId, max: Long, regenPerSec: Double): ManaPool
}
