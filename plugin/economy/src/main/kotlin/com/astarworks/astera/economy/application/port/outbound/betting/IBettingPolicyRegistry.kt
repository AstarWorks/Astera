package com.astarworks.astera.economy.application.port.outbound.betting

import com.astarworks.astera.economy.domain.betting.BettingPolicy
import com.astarworks.astera.economy.domain.betting.BettingPolicyId

/**
 * Outbound port: read [BettingPolicy] by id (Wave 5bet / ADR-0103).
 *
 * Loaded from `content/bet-policies/<id>.yaml` (kaml-deserialised
 * `BettingPolicyYamlConfig`). The server's "active default policy id" is
 * configured on `AsteraEconomyPlugin` wire-up (default: `default`).
 */
public interface IBettingPolicyRegistry {

    public fun get(id: BettingPolicyId): BettingPolicy?

    public fun all(): Collection<BettingPolicy>
}
