package com.astarworks.astera.core.domain.anticheat

/**
 * Outcome of [com.astarworks.astera.core.application.port.outbound.anticheat.IInteractValidator.validate].
 */
public sealed interface InteractValidation {

    public data object Ok : InteractValidation

    /**
     * The actor exceeded the per-action rate cap. [measuredRate] is in
     * actions-per-second; [cap] is what the validator considers human-plausible.
     */
    public data class RateExceeded(val measuredRate: Double, val cap: Double) : InteractValidation
}
