package com.astarworks.astera.match.domain.generator

import com.astarworks.astera.core.domain.attribute.IAttributeContainer
import com.astarworks.astera.core.domain.economy.Currency
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
 * - [currency] — what it produces ([Currency.STAR], etc.).
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
    val currency: Currency,
    val period: Duration,
    val amount: Int,
    val tier: GeneratorTier,
    val affiliation: Affiliation,
    val attributes: IAttributeContainer,
) {
    init {
        require(amount >= 1) { "StarGenerator.amount must be >= 1 (was $amount)" }
        require(period.isPositive()) { "StarGenerator.period must be positive (was $period)" }
    }
}
