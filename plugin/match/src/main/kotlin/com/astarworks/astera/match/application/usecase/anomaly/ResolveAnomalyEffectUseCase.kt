package com.astarworks.astera.match.application.usecase.anomaly

import com.astarworks.astera.core.domain.attribute.AttributeModifier
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.application.port.outbound.IGeneratorRepository
import com.astarworks.astera.match.domain.anomaly.AnomalyDescriptor
import com.astarworks.astera.match.domain.anomaly.AnomalyId
import com.astarworks.astera.match.domain.anomaly.AnomalyType
import com.astarworks.astera.match.domain.generator.GeneratorAttributes

/**
 * Materialize an [AnomalyDescriptor] as a real
 * [AttributeModifier] on the relevant subject(s) (Wave 5c).
 *
 * Pure application logic. Today only [AnomalyType.ResourceSpike] has a live
 * subject — every [com.astarworks.astera.match.domain.generator.StarGenerator]
 * in the match receives a modifier on
 * [GeneratorAttributes.PERIOD_MULTIPLIER]. The other five variants are
 * scaffolded so the scheduler can still publish their
 * [com.astarworks.astera.match.domain.event.AnomalyTriggered] events; the
 * actual effect plumbing lands in Wave 6 once the relevant containers
 * (`IStatusEffectContainer`, player container) are wired.
 *
 * The [generators] dependency is **nullable** so unit tests that focus on
 * announcement / scheduling can wire `null` and skip the generator
 * round-trip; Phase C in `AsteraMatchPlugin.kt` always passes the real
 * repository.
 *
 * ## Source-id convention
 *
 * Every modifier produced by [apply] tags itself with
 * `sourceId = "anomaly:<anomalyId>"`. [expire] removes by the same tag,
 * giving us O(modifiers) bulk teardown without tracking a separate handle
 * per (generator, modifier) pair.
 */
public class ResolveAnomalyEffectUseCase(
    private val generators: IGeneratorRepository?,
) {

    /**
     * Apply the descriptor's effect for [anomalyId] in [matchId].
     *
     * Behaviour by [AnomalyDescriptor.type]:
     *
     * - [AnomalyType.ResourceSpike] — add a modifier to every generator in
     *   the match using [AnomalyDescriptor.modifierOperation] +
     *   [AnomalyDescriptor.modifierValue] on
     *   [GeneratorAttributes.PERIOD_MULTIPLIER]. No-op if [generators] is
     *   `null` (test wiring).
     * - all other variants currently no-op (see kdoc above for the Wave 6
     *   carry-overs).
     */
    public fun apply(anomalyId: AnomalyId, matchId: MatchId, descriptor: AnomalyDescriptor) {
        when (descriptor.type) {
            AnomalyType.ResourceSpike -> applyResourceSpike(anomalyId, matchId, descriptor)
            // TODO(Wave 6): wire StatusBurst into IStatusEffectContainer.
            AnomalyType.StatusBurst -> Unit
            // TODO(Wave 6): wire SpawnSurge into respawn-rate attribute.
            AnomalyType.SpawnSurge -> Unit
            // TODO(Wave 6): wire TimeWarp into match-timer pace.
            AnomalyType.TimeWarp -> Unit
            // TODO(Wave 6): wire GravityShift into player movement.
            AnomalyType.GravityShift -> Unit
            // TODO(Wave 6): wire FogOfWar into visibility / render distance.
            AnomalyType.FogOfWar -> Unit
        }
    }

    /**
     * Remove every modifier tagged `anomaly:<anomalyId>` from every generator
     * in [matchId]. Called when the anomaly's [AnomalyDescriptor.durationTicks]
     * elapse (scheduled via [com.astarworks.astera.core.application.port.outbound.IScheduler]
     * by [com.astarworks.astera.match.application.service.AnomalyScheduler]).
     */
    public fun expire(anomalyId: AnomalyId, matchId: MatchId) {
        val repo = generators ?: return
        val sourceTag = sourceTag(anomalyId)
        for (generator in repo.listForMatch(matchId)) {
            generator.attributes.removeModifiersBySource(sourceTag)
        }
    }

    private fun applyResourceSpike(
        anomalyId: AnomalyId,
        matchId: MatchId,
        descriptor: AnomalyDescriptor,
    ) {
        val repo = generators ?: return
        val modifier = AttributeModifier(
            key = GeneratorAttributes.PERIOD_MULTIPLIER,
            operation = descriptor.modifierOperation,
            value = descriptor.modifierValue,
            sourceId = sourceTag(anomalyId),
        )
        for (generator in repo.listForMatch(matchId)) {
            generator.attributes.addModifier(modifier)
        }
    }

    private fun sourceTag(anomalyId: AnomalyId): String = "anomaly:${anomalyId.value}"
}
