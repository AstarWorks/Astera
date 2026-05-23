package com.astarworks.astera.economy.application.service.betting

import com.astarworks.astera.economy.application.port.outbound.betting.IBettingPolicyRegistry
import com.astarworks.astera.economy.domain.betting.BettingPolicy
import com.astarworks.astera.economy.domain.betting.BettingPolicyId

/**
 * Phase 1 in-memory [IBettingPolicyRegistry] (Wave 5bet / ADR-0103).
 *
 * Seeded at boot from `content/bet-policies/<id>.yaml` via
 * [com.astarworks.astera.economy.application.contents.betting.BettingPolicyContents].
 * The single Phase 1 default policy lives in
 * `content/bet-policies/default.yaml`.
 */
public class InMemoryBettingPolicyRegistry(
    seed: Iterable<BettingPolicy> = emptyList(),
) : IBettingPolicyRegistry {

    private val byId: MutableMap<BettingPolicyId, BettingPolicy> = LinkedHashMap()

    init {
        seed.forEach { register(it) }
    }

    public fun register(policy: BettingPolicy) {
        byId[policy.id] = policy
    }

    override fun get(id: BettingPolicyId): BettingPolicy? = byId[id]

    override fun all(): Collection<BettingPolicy> = byId.values.toList()
}
