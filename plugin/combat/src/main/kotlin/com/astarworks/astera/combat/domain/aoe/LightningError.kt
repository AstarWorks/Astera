package com.astarworks.astera.combat.domain.aoe

import com.astarworks.astera.core.domain.world.WorldId

/** Failure modes for `ILightningGateway.strike`. */
public sealed interface LightningError {

    /** Target world is not loaded. */
    public data class WorldNotLoaded(public val world: WorldId) : LightningError

    /** Backend impl is not available — only the placeholder reports this. */
    public data object NoImplementationBound : LightningError
}
