package com.astarworks.astera.match.application.usecase.anomaly

import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.application.port.outbound.IPlayerMovementGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerVisionGateway
import com.astarworks.astera.core.domain.attribute.AttributeModifier
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.port.outbound.IGeneratorRepository
import com.astarworks.astera.match.application.port.outbound.IMatchTimerService
import com.astarworks.astera.match.domain.anomaly.AnomalyDescriptor
import com.astarworks.astera.match.domain.anomaly.AnomalyId
import com.astarworks.astera.match.domain.anomaly.AnomalyType
import com.astarworks.astera.match.domain.generator.GeneratorAttributes
import com.astarworks.astera.match.domain.player.PlayerMatchAttributes

/**
 * Materialize an [AnomalyDescriptor] as a real effect on the relevant
 * subject(s) (Wave 5c → Wave 5g).
 *
 * Pure application logic. Six variants per [AnomalyType]:
 *
 * - [AnomalyType.ResourceSpike] — modifier on every generator's
 *   [GeneratorAttributes.PERIOD_MULTIPLIER] (Wave 5c).
 * - [AnomalyType.StatusBurst] — partial wire: this use case depends on
 *   [IStatusEffectContainer] (ADR-0072 commitment) but Wave 5g does not yet
 *   resolve the descriptor's payload into concrete `StatusEffectInstance`s.
 *   Wave 6a (Paper Sweep) ships the content-driven anomaly → StatusEffect
 *   mapping; the entry point on this port is [IStatusEffectContainer.applyBulk].
 * - [AnomalyType.SpawnSurge] — modifier on every active player's
 *   [PlayerMatchAttributes.RESPAWN_RATE_MULTIPLIER] container (Wave 5g).
 * - [AnomalyType.TimeWarp] — `IMatchTimerService.shiftPhaseTimer` (Wave 5g,
 *   one-shot — no expire-side undo).
 * - [AnomalyType.GravityShift] — `IPlayerMovementGateway.setGravityMultiplier`
 *   with TTL = duration (Wave 5g, gateway auto-resets at TTL).
 * - [AnomalyType.FogOfWar] — `IPlayerVisionGateway.setFogRadius` with TTL =
 *   duration (Wave 5g, gateway auto-resets at TTL). Whether the Wave 6a
 *   Paper adapter implements this by applying a vanilla `BLINDNESS` potion,
 *   shrinking render distance, or both, is a Paper-side decision (ADR-0072).
 *
 * Every collaborator is **nullable** so unit tests can wire only the
 * subset they care about; Phase C in `AsteraMatchPlugin.kt` passes the
 * real impls (with the Wave 6 Paper adapters overriding Fakes via
 * priority-based [com.astarworks.astera.core.application.services.ServiceRegistry]).
 *
 * ## Source-id convention
 *
 * Every attribute modifier produced by [apply] tags itself with
 * `sourceId = "anomaly:<anomalyId>"`. [expire] removes by the same tag,
 * giving us O(modifiers) bulk teardown without tracking a separate handle
 * per (subject, modifier) pair. Movement / vision gateways own their own
 * TTL-driven reset; timer shifts are one-shot so there is nothing to
 * unshift at expire.
 */
public class ResolveAnomalyEffectUseCase(
    private val generators: IGeneratorRepository?,
    private val statusEffectContainer: IStatusEffectContainer? = null,
    private val matchTimerService: IMatchTimerService? = null,
    private val playerMovementGateway: IPlayerMovementGateway? = null,
    private val playerVisionGateway: IPlayerVisionGateway? = null,
    private val playerAttributeStore: IPlayerAttributeContainerStore? = null,
    private val matchPlayerListProvider: ((MatchId) -> Collection<PlayerId>)? = null,
) {

    /**
     * Apply the descriptor's effect for [anomalyId] in [matchId].
     *
     * Per-variant behaviour is documented in the class kdoc; each branch
     * silently no-ops if the required collaborator is `null` so tests can
     * wire only what they need.
     */
    public fun apply(anomalyId: AnomalyId, matchId: MatchId, descriptor: AnomalyDescriptor) {
        when (descriptor.type) {
            AnomalyType.ResourceSpike -> applyResourceSpike(anomalyId, matchId, descriptor)
            AnomalyType.StatusBurst -> applyStatusBurst(matchId, descriptor)
            AnomalyType.SpawnSurge -> applySpawnSurge(anomalyId, matchId, descriptor)
            AnomalyType.TimeWarp -> applyTimeWarp(matchId, descriptor)
            AnomalyType.GravityShift -> applyGravityShift(matchId, descriptor)
            AnomalyType.FogOfWar -> applyFogOfWar(matchId, descriptor)
        }
    }

    /**
     * Remove every modifier tagged `anomaly:<anomalyId>` from every subject
     * in [matchId]. Called when the anomaly's [AnomalyDescriptor.durationTicks]
     * elapse.
     *
     * Generator modifiers (ResourceSpike) and per-player attribute modifiers
     * (SpawnSurge) are removed via the attribute container source-tag sweep.
     * Movement & vision gateways own their own TTL-driven reset (no explicit
     * call needed); timer shifts are one-shot. StatusBurst expire is a
     * no-op in Wave 5g (no concrete effects applied yet — see class kdoc).
     */
    public fun expire(anomalyId: AnomalyId, matchId: MatchId) {
        val sourceTag = sourceTag(anomalyId)
        generators?.listForMatch(matchId)?.forEach { generator ->
            generator.attributes.removeModifiersBySource(sourceTag)
        }
        val store = playerAttributeStore
        val players = matchPlayerListProvider?.invoke(matchId)
        if (store != null && players != null) {
            for (playerId in players) {
                store.containerFor(playerId).removeModifiersBySource(sourceTag)
            }
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

    private fun applyStatusBurst(matchId: MatchId, descriptor: AnomalyDescriptor) {
        // Wave 5g partial wire: the dependency on IStatusEffectContainer is
        // committed (ADR-0072); the data path — descriptor → concrete
        // StatusEffectInstance collection — lands in Wave 6a with
        // content-driven anomaly→effect mapping. Call site here uses the
        // applyBulk entry point so the wire is exercised once data is wired.
        val container = statusEffectContainer ?: return
        val players = matchPlayerListProvider?.invoke(matchId) ?: return
        if (players.isEmpty()) return
        // Empty collection: applyBulk is a no-op until Wave 6a populates
        // the instance list. The dependency edge stays live for static analysis.
        container.applyBulk(emptyList())
    }

    private fun applySpawnSurge(
        anomalyId: AnomalyId,
        matchId: MatchId,
        descriptor: AnomalyDescriptor,
    ) {
        val store = playerAttributeStore ?: return
        val players = matchPlayerListProvider?.invoke(matchId) ?: return
        val modifierTemplate = AttributeModifier(
            key = PlayerMatchAttributes.RESPAWN_RATE_MULTIPLIER,
            operation = descriptor.modifierOperation,
            value = descriptor.modifierValue,
            sourceId = sourceTag(anomalyId),
        )
        for (playerId in players) {
            store.containerFor(playerId).addModifier(modifierTemplate)
        }
    }

    private fun applyTimeWarp(matchId: MatchId, descriptor: AnomalyDescriptor) {
        val service = matchTimerService ?: return
        val signedDelta: Long = (descriptor.durationTicks.toDouble() * descriptor.modifierValue).toLong()
        val deltaTicks: Int = signedDelta.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
        service.shiftPhaseTimer(matchId, deltaTicks)
    }

    private fun applyGravityShift(matchId: MatchId, descriptor: AnomalyDescriptor) {
        val gateway = playerMovementGateway ?: return
        val players = matchPlayerListProvider?.invoke(matchId) ?: return
        val ttl: Int = descriptor.durationTicks.toIntClampedTicks()
        for (playerId in players) {
            gateway.setGravityMultiplier(
                playerId = playerId,
                multiplier = descriptor.modifierValue,
                ttlTicks = ttl,
            )
        }
    }

    private fun applyFogOfWar(matchId: MatchId, descriptor: AnomalyDescriptor) {
        val vision = playerVisionGateway ?: return
        val players = matchPlayerListProvider?.invoke(matchId) ?: return
        val ttl: Int = descriptor.durationTicks.toIntClampedTicks()
        val radius: Int = descriptor.modifierValue.toInt().coerceAtLeast(MIN_FOG_RADIUS)
        for (playerId in players) {
            vision.setFogRadius(playerId, radiusBlocks = radius, ttlTicks = ttl)
        }
    }

    private fun sourceTag(anomalyId: AnomalyId): String = "anomaly:${anomalyId.value}"

    /** Clamp a `Long` tick count to a positive `Int` so port impls have a safe value. */
    private fun Long.toIntClampedTicks(): Int =
        this.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()

    private companion object {
        /** Floor for FogOfWar radius: vision at 0 blocks would be a crash hazard. */
        private const val MIN_FOG_RADIUS: Int = 4
    }
}
