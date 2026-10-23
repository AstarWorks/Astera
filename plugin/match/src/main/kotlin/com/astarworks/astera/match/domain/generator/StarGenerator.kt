package com.astarworks.astera.match.domain.generator

import com.astarworks.astera.core.domain.attribute.IAttributeContainer
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import kotlin.time.Duration

/**
 * One placed Star Generator inside a match (Wave 5c).
 *
 * A generator accumulates [GeneratorAttributes.ENERGY] over time; once it
 * crosses its effective period
 * (`period * effective(GeneratorAttributes.PERIOD_MULTIPLIER)`) the scheduler
 * publishes a
 * [com.astarworks.astera.match.domain.event.ResourceSpawned] event with
 * `amount * effective(GeneratorAttributes.AMOUNT_MULTIPLIER)`.
 *
 * Per ADR-0058 §5 the runtime state — current charge, period / amount
 * multipliers, anomaly overlays — lives **inside** [attributes]
 * ([com.astarworks.astera.core.domain.attribute.SimpleAttributeContainer] in
 * practice). Fields outside the container are the *immutable spec* of the
 * generator authored in `content/generators/`:
 *
 * - [id] / [matchId] — identity within a match.
 * - [position] — world placement (vendor-neutral [Vec3]).
 * - [stockpileCap] — most stars the generator holds uncollected at its position.
 * - [lateGameMultiplier] — factor on each pop once the match is in sudden death (generators pay more late).
 * - [pickupRadius] — blocks within which a participant collects the stockpile.
 * - [period] — base interval between spawns ([kotlin.time.Duration]).
 * - [amount] — base per-spawn quantity (integer; the AMOUNT_MULTIPLIER
 *   modifier may scale it).
 * - [tier] — display tier for HUD rendering.
 * - [affiliation] — ownership ([Affiliation.Neutral] /
 *   [Affiliation.Team]).
 *
 * The generator itself is a [data class] so equality / copy stay structural;
 * the [attributes] container is intentionally `IAttributeContainer` rather
 * than the concrete type so persistence (Wave 5e V14 JSONB) may swap in a
 * different impl without churning use cases.
 */
public data class StarGenerator(
    val id: GeneratorId,
    val matchId: MatchId,
    val position: Vec3,
    val period: Duration,
    val amount: Int,
    val tier: GeneratorTier,
    val affiliation: Affiliation,
    val attributes: IAttributeContainer,
    val stockpileCap: Int = DEFAULT_STOCKPILE_CAP,
    val pickupRadius: Double = DEFAULT_PICKUP_RADIUS,
    val lateGameMultiplier: Double = 1.0,
) {
    init {
        require(amount >= 1) { "StarGenerator.amount must be >= 1 (was $amount)" }
        require(stockpileCap >= 1) { "StarGenerator.stockpileCap must be >= 1 (was $stockpileCap)" }
        require(lateGameMultiplier >= 1.0) { "StarGenerator.lateGameMultiplier must be >= 1 (was $lateGameMultiplier)" }
        require(pickupRadius > 0.0) { "StarGenerator.pickupRadius must be > 0 (was $pickupRadius)" }
        require(period.isPositive()) { "StarGenerator.period must be positive (was $period)" }
    }

    public companion object {
        public const val DEFAULT_STOCKPILE_CAP: Int = 5
        public const val DEFAULT_PICKUP_RADIUS: Double = 3.0
    }
}
