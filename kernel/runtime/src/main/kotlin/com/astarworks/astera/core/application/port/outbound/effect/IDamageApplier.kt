package com.astarworks.astera.core.application.port.outbound.effect

import com.astarworks.astera.core.domain.damage.DamageProfile
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Cross-plugin bridge: apply damage to a player.
 *
 * Wave 5effects (ADR-0109). Sibling to [IStatusEffectApplier]; combat
 * registers `ApplyDamageUseCase`-backed impl at priority 100, core ships
 * a no-op default at priority 0.
 *
 * Kept intentionally minimal — the orchestrator does not need the full
 * `DamageAttempt` (source / casterPosition / sourceItem) because
 * `EffectChain.DamageStep` is descriptive ("apply this profile to this
 * target"); attribution metadata belongs upstream where the chain is
 * built.
 */
public interface IDamageApplier {
    public fun apply(target: PlayerId, profile: DamageProfile)
}
