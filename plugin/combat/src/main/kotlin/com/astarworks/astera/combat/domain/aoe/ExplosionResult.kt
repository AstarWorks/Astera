package com.astarworks.astera.combat.domain.aoe

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Successful outcome of [com.astarworks.astera.combat.application.port.outbound.aoe.IExplosionGateway.trigger]
 * (Wave 5m / ADR-0088).
 *
 * @property affectedPlayers Pairs of `(playerId, damageDealt)`. The
 *   gateway populates this from the per-entity `ApplyDamageUseCase`
 *   invocations it performs internally. Order is implementation-defined
 *   (typically nearest-first); tests should not depend on order.
 * @property affectedBlocks Block count actually broken (post blast-
 *   resistance for [BlockDamagePolicy.Vanilla], post ratio for
 *   [BlockDamagePolicy.Astera], `0` for [BlockDamagePolicy.None]).
 */
public data class ExplosionResult(
    public val affectedPlayers: List<Pair<PlayerId, Double>>,
    public val affectedBlocks: Int,
) {
    init {
        require(affectedBlocks >= 0) {
            "ExplosionResult.affectedBlocks must be >= 0: $affectedBlocks"
        }
        affectedPlayers.forEach { (_, dmg) ->
            require(dmg >= 0.0) {
                "ExplosionResult: per-player damage must be >= 0: $dmg"
            }
        }
    }
}

/**
 * Failure modes for [com.astarworks.astera.combat.application.port.outbound.aoe.IExplosionGateway.trigger].
 *
 * Per ADR-0001 the application API never throws; expected failures are
 * values.
 */
public sealed interface ExplosionError {

    /** Target world is not loaded. */
    public data class WorldNotLoaded(public val world: WorldId) : ExplosionError

    /**
     * No runtime impl bound (Wave 5m default = InMemory; Wave 6a Paper
     * impl overrides at priority 100). Returned only by the
     * `UnboundExplosionGateway` placeholder which Combat installs when no
     * impl is registered — kept here so callers that opt in to the
     * placeholder still get an exhaustive `when`.
     */
    public data object NoImplementationBound : ExplosionError

    /** [com.astarworks.astera.combat.domain.aoe.ExplosionSpec.radius] exceeds the configured server hard-cap. */
    public data class RadiusExceedsCap(public val requested: Double, public val cap: Double) : ExplosionError
}
