package com.astarworks.astera.match.application.usecase.match

import com.astarworks.astera.core.application.port.outbound.EventDelivery

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.attribute.AttributeModifier
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.match.application.port.outbound.IGeneratorRepository
import com.astarworks.astera.match.domain.event.PhaseChanged
import com.astarworks.astera.match.domain.generator.GeneratorAttributes

/**
 * On entering [MatchPhase.SUDDEN_DEATH], accelerate every generator in the
 * match by overlaying a `MULTIPLY 0.5` modifier on
 * [GeneratorAttributes.PERIOD_MULTIPLIER]; when the phase leaves
 * `SUDDEN_DEATH`, remove the overlay (Wave 5c, ADR-0032 / ADR-0058).
 *
 * ## Source-id convention
 *
 * Every modifier produced here tags itself with
 * `sourceId = "sudden-death:<matchId>"`, mirroring the
 * `anomaly:<anomalyId>` pattern used by `ResolveAnomalyEffectUseCase`. Bulk
 * teardown then uses
 * [com.astarworks.astera.core.domain.attribute.IAttributeContainer.removeModifiersBySource]
 * without tracking per-(generator, modifier) handles.
 *
 * ## Why a modifier, not a phase-conditional `if` in the spawn loop
 *
 * Per ADR-0058 §5, "runtime tweaks live as attribute modifiers, not as
 * bespoke flags on the use case." Both Sudden Death and Anomaly use the same
 * primitive on the same key; HUD widgets reading
 * `IAttributeContainer.effectiveNumber(PERIOD_MULTIPLIER)` see a single
 * uniform truth.
 */
public class SuddenDeathAcceleratorUseCase(
    private val eventBus: IDomainEventBus,
    private val generators: IGeneratorRepository,
) {

    /**
     * Subscribe to [PhaseChanged] events. Returns the subscription so the
     * caller (Phase C `AsteraMatchPlugin.onDisable`) can cancel it.
     */
    public fun subscribe(): IDomainEventBus.Subscription =
        eventBus.subscribe(PhaseChanged::class, EventDelivery.Global) { event -> onPhaseChanged(event) }

    /** Visible for tests; exercised through `subscribe` in production. */
    public fun onPhaseChanged(event: PhaseChanged) {
        when {
            event.to == MatchPhase.SUDDEN_DEATH && event.from != MatchPhase.SUDDEN_DEATH ->
                applyAcceleration(event.matchId)
            event.from == MatchPhase.SUDDEN_DEATH && event.to != MatchPhase.SUDDEN_DEATH ->
                removeAcceleration(event.matchId)
            else -> Unit
        }
    }

    private fun applyAcceleration(matchId: MatchId) {
        val modifier = AttributeModifier(
            key = GeneratorAttributes.PERIOD_MULTIPLIER,
            operation = AttributeModifier.Operation.MULTIPLY,
            value = ACCELERATION_FACTOR,
            sourceId = sourceTag(matchId),
        )
        for (generator in generators.listForMatch(matchId)) {
            generator.attributes.addModifier(modifier)
        }
    }

    private fun removeAcceleration(matchId: MatchId) {
        val tag = sourceTag(matchId)
        for (generator in generators.listForMatch(matchId)) {
            generator.attributes.removeModifiersBySource(tag)
        }
    }

    private fun sourceTag(matchId: MatchId): String = "sudden-death:${matchId.value}"

    public companion object {
        /**
         * Multiplicative factor applied to `generator.period_multiplier`
         * while a match is in [MatchPhase.SUDDEN_DEATH]. `0.5` halves the
         * effective period, doubling generator speed.
         */
        public const val ACCELERATION_FACTOR: Double = 0.5
    }
}
