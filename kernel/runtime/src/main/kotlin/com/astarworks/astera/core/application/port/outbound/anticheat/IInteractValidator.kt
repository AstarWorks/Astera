package com.astarworks.astera.core.application.port.outbound.anticheat

import com.astarworks.astera.core.domain.anticheat.InteractAction
import com.astarworks.astera.core.domain.anticheat.InteractValidation
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port — rate-cap on discrete interactions (clicks, container
 * opens, item uses). Counterpart to [IMovementValidator] and
 * [IDamageRateValidator] in the Wave 5q (ADR-0083) validator triad.
 */
public interface IInteractValidator {

    public fun validate(
        playerId: PlayerId,
        action: InteractAction,
        atTick: Long,
    ): InteractValidation
}
