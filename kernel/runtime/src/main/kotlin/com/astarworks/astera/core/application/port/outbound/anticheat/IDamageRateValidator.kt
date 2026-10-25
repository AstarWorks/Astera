package com.astarworks.astera.core.application.port.outbound.anticheat

import com.astarworks.astera.core.domain.anticheat.DamageRateValidation
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port — sliding-window DPS gate.
 *
 * Called from the Wave 5a damage pipeline before applying any damage event
 * the player is the attacker of. Implementations sample dps in a short
 * window (default ≈ 1s) and return [DamageRateValidation.TooFast] when the
 * caller should swallow the hit.
 *
 * Per-weapon, per-role caps fold in upstream — the validator just sees the
 * effective cap on each call. Wave 5a-fix2 (ADR-0063) already keeps an
 * attribution window we can reuse on the impl side.
 */
public interface IDamageRateValidator {

    public fun validate(
        attackerId: PlayerId,
        victimId: PlayerId,
        atTick: Long,
    ): DamageRateValidation
}
