package com.astarworks.astera.core.domain.anticheat

/**
 * Outcome of [com.astarworks.astera.core.application.port.outbound.anticheat.IDamageRateValidator.validate].
 *
 * `dps` (damage-per-second) is sampled across a small sliding window; when it
 * exceeds the configured cap the validator yields [TooFast]. The cap fold-in
 * (per-weapon, per-role) lives in adapter / use-case land — the domain just
 * carries the result.
 */
public sealed interface DamageRateValidation {

    public data object Ok : DamageRateValidation

    /**
     * Damage was applied at a higher rate than the validator allows.
     * Carries both the measured dps and the cap for downstream reporting
     * (e.g. [com.astarworks.astera.core.domain.anticheat.AnticheatSuspicion.DamageRate]).
     */
    public data class TooFast(val measuredDps: Double, val cap: Double) : DamageRateValidation
}
