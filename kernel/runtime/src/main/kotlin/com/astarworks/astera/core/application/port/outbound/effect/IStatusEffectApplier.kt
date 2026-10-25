package com.astarworks.astera.core.application.port.outbound.effect

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.status.StatusEffectId

/**
 * Cross-plugin bridge: apply a status effect to a player.
 *
 * Wave 5effects (ADR-0109). Concrete impl lives in `:plugin:combat`
 * (`ApplyStatusUseCase` adapter); Core ships a no-op default that
 * warn-logs when invoked. The orchestrator uses this SPI to fulfill
 * `EffectStep.StatusEffectStep` without violating ADR-0022 sibling
 * isolation (combat → core port → orchestrator → port impl).
 *
 * # Lifecycle
 *
 * Registered by `AsteraCombatPlugin.onEnable()` at priority 100. The
 * Core default sits at priority 0 (no-op) so the orchestrator can boot
 * before combat without an NPE.
 */
public interface IStatusEffectApplier {
    public fun apply(target: PlayerId, effectId: StatusEffectId, durationTicks: Int)
}
