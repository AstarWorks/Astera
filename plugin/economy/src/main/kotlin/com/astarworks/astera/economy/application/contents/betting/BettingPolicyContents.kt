package com.astarworks.astera.economy.application.contents.betting

import com.astarworks.astera.core.application.contents.YamlDynamicContents
import com.astarworks.astera.economy.application.config.betting.BettingPolicyYamlConfig
import com.astarworks.astera.economy.application.port.outbound.betting.IBettingPolicyRegistry
import com.astarworks.astera.economy.domain.betting.BettingPolicy
import com.astarworks.astera.economy.domain.betting.BettingPolicyId

/**
 * `Contents<BettingPolicyId, BettingPolicy>` (Wave 5bet / ADR-0103).
 *
 * Bootstrap directory: `content/bet-policies/`. Atomic-swap reload via
 * `/astera admin reload bet-policies` (substrate from ADR-0065).
 *
 * Also implements [IBettingPolicyRegistry] so the in-memory default registry
 * is replaced wholesale at boot — same pattern as `EncounterZoneContents`
 * implementing `IEncounterZoneRegistry` (ADR-0099).
 */
public class BettingPolicyContents :
    YamlDynamicContents<BettingPolicyId, BettingPolicy, BettingPolicyYamlConfig>(
        categoryName = "bet-policies",
        deserializer = BettingPolicyYamlConfig.serializer(),
    ),
    IBettingPolicyRegistry {

    override fun toEntry(config: BettingPolicyYamlConfig): Pair<BettingPolicyId, BettingPolicy> {
        val policy = config.toDomain()
        return policy.id to policy
    }

    override fun get(id: BettingPolicyId): BettingPolicy? = find(id)

    /** Convenience overload — register by policy alone. */
    public fun register(policy: BettingPolicy) {
        register(policy.id, policy)
    }
}
