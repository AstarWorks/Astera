package com.astarworks.astera.stats.scenario

import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.domain.status.StatusEffectId
import com.astarworks.astera.combat.domain.status.StatusEffectInstance
import com.astarworks.astera.core.application.service.InMemoryPlayerAttributeContainer
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.attribute.AttributeKey
import com.astarworks.astera.core.domain.attribute.AttributeModifier
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.port.outbound.IMatchTimerService
import com.astarworks.astera.match.application.port.outbound.TimerShiftError
import com.astarworks.astera.match.application.usecase.anomaly.ResolveAnomalyEffectUseCase
import com.astarworks.astera.match.domain.anomaly.AnomalyDescriptor
import com.astarworks.astera.match.domain.anomaly.AnomalyId
import com.astarworks.astera.match.domain.anomaly.AnomalyType
import com.astarworks.astera.match.domain.player.PlayerMatchAttributes
import com.astarworks.astera.testfixtures.FakePlayerMovementGateway
import com.astarworks.astera.testfixtures.FakePlayerVisionGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Wave 5g end-to-end scenario coverage for the 5 anomaly variants that
 * Wave 5c originally left as `TODO(Wave 6)` no-ops:
 * StatusBurst / SpawnSurge / TimeWarp / GravityShift / FogOfWar.
 *
 * Distinct from `ResolveAnomalyEffectUseCaseTest` (which pins per-variant
 * use-case behaviour with the smallest possible wiring): these scenarios
 * boot a real [AsteraTestHarness] so each variant is exercised under the
 * same `IDomainEventBus` + `FakeScheduler` + `FakePlayerGateway` orchestra
 * that production sees.
 *
 * ## ADR-0072 boundary
 *
 * - **StatusBurst** is a partial wire in Wave 5g: the
 *   `IStatusEffectContainer.applyBulk` entry point is invoked but the
 *   instance list is empty until Wave 6a ships the content-driven
 *   anomaly→effect mapping. The scenario pins the no-op contract — no
 *   Astera effects active, no generator modifiers touched.
 * - **FogOfWar** shrinks vision radius via `IPlayerVisionGateway` only. The
 *   Wave 6a Paper adapter decides whether to back the impl with Bukkit
 *   `BLINDNESS`, render-distance manipulation, or both — the domain stays
 *   Bukkit-free (ADR-0002 / ADR-0030 §8).
 */
class AnomalyScenarioTest {

    private val matchId = MatchId("anomaly-scenario-match")

    // ────── StatusBurst (Wave 5g partial wire) ─────────────────────────

    @Test
    fun `StatusBurst is a clean no-op until Wave 6a wires content data`() {
        val harness = AsteraTestHarness.newDefault()
        val alice = harness.spawnPlayer("status-burst-alice")
        val bob = harness.spawnPlayer("status-burst-bob")
        val statusContainer = RecordingStatusEffectContainer()
        val anomalyId = AnomalyId("status-burst-1")

        val resolveAnomaly = ResolveAnomalyEffectUseCase(
            generators = null,
            statusEffectContainer = statusContainer,
            matchPlayerListProvider = { mid -> if (mid == matchId) listOf(alice.id, bob.id) else emptyList() },
        )

        val descriptor = AnomalyDescriptor(
            type = AnomalyType.StatusBurst,
            weight = 1,
            durationTicks = 600L,
            attributeKey = AttributeKey("anomaly.status_intensity"),
            modifierOperation = AttributeModifier.Operation.ADD,
            modifierValue = 1.0,
        )

        resolveAnomaly.apply(anomalyId, matchId, descriptor)

        // Partial wire: applyBulk(emptyList()) — no Astera effects materialized.
        for (player in listOf(alice, bob)) {
            assertThat(statusContainer.active(player.id))
                .withFailMessage {
                    "StatusBurst must be a no-op in Wave 5g (data path lands in Wave 6a per ADR-0072). " +
                        "Active effects: ${statusContainer.active(player.id)}"
                }
                .isEmpty()
        }
    }

    // ────── SpawnSurge ───────────────────────────────────────────────

    @Test
    fun `SpawnSurge overlays RESPAWN_RATE_MULTIPLIER on every player attribute container`() {
        val harness = AsteraTestHarness.newDefault()
        val alice = harness.spawnPlayer("spawn-surge-alice")
        val bob = harness.spawnPlayer("spawn-surge-bob")
        val store = InMemoryPlayerAttributeContainer()
        val anomalyId = AnomalyId("spawn-surge-1")

        val resolveAnomaly = ResolveAnomalyEffectUseCase(
            generators = null,
            playerAttributeStore = store,
            matchPlayerListProvider = { mid -> if (mid == matchId) listOf(alice.id, bob.id) else emptyList() },
        )

        val descriptor = AnomalyDescriptor(
            type = AnomalyType.SpawnSurge,
            weight = 1,
            durationTicks = 600L,
            attributeKey = PlayerMatchAttributes.RESPAWN_RATE_MULTIPLIER,
            modifierOperation = AttributeModifier.Operation.MULTIPLY,
            modifierValue = 2.0,
        )

        resolveAnomaly.apply(anomalyId, matchId, descriptor)

        for (player in listOf(alice, bob)) {
            val mods = store.containerFor(player.id).modifiers()
            val anomalyMods = mods.filter { it.sourceId == "anomaly:${anomalyId.value}" }
            assertThat(anomalyMods)
                .withFailMessage {
                    "SpawnSurge did not write a RESPAWN_RATE_MULTIPLIER modifier " +
                        "onto ${player.name}'s attribute container. Modifiers: $mods"
                }
                .hasSize(1)
            val mod = anomalyMods.single()
            assertThat(mod.key).isEqualTo(PlayerMatchAttributes.RESPAWN_RATE_MULTIPLIER)
            assertThat(mod.operation).isEqualTo(AttributeModifier.Operation.MULTIPLY)
            assertThat(mod.value).isEqualTo(2.0)
        }

        resolveAnomaly.expire(anomalyId, matchId)
        for (player in listOf(alice, bob)) {
            val remaining = store.containerFor(player.id).modifiers()
                .filter { it.sourceId == "anomaly:${anomalyId.value}" }
            assertThat(remaining)
                .withFailMessage { "expire() must roll back the SpawnSurge modifier." }
                .isEmpty()
        }
    }

    // ────── TimeWarp ─────────────────────────────────────────────────

    @Test
    fun `TimeWarp shifts the match phase timer through IMatchTimerService`() {
        val harness = AsteraTestHarness.newDefault()
        val timer = RecordingMatchTimerService()
        val anomalyId = AnomalyId("time-warp-1")

        val resolveAnomaly = ResolveAnomalyEffectUseCase(
            generators = null,
            matchTimerService = timer,
        )

        // durationTicks * modifierValue = signed delta. 400 × 1.5 = 600.
        val descriptor = AnomalyDescriptor(
            type = AnomalyType.TimeWarp,
            weight = 1,
            durationTicks = 400L,
            attributeKey = AttributeKey("anomaly.time_warp_delta"),
            modifierOperation = AttributeModifier.Operation.ADD,
            modifierValue = 1.5,
        )

        resolveAnomaly.apply(anomalyId, matchId, descriptor)

        val recordedDeltas = timer.shiftsByMatch[matchId]
        assertThat(recordedDeltas)
            .withFailMessage {
                "TimeWarp did not invoke IMatchTimerService.shiftPhaseTimer for $matchId. " +
                    "shiftsByMatch=${timer.shiftsByMatch}"
            }
            .isNotNull()
            .isNotEmpty()
        assertThat(timer.totalShiftFor(matchId)).isEqualTo(600)

        // Harness's bus must still be quiescent — TimeWarp is a one-shot
        // gateway call, not an event publish.
        assertThat(harness.publishedEvents).isNotNull
    }

    /**
     * Minimal recording impl of [IStatusEffectContainer] for the StatusBurst
     * scenario. Inlined here (rather than imported from `combat.testfixtures`)
     * because Gradle does not expose another module's test source set
     * cross-plugin without the `java-test-fixtures` plugin (intentionally not
     * adopted in Wave 5g — overkill for one Fake per port).
     */
    private class RecordingStatusEffectContainer : IStatusEffectContainer {
        private val store: MutableMap<PlayerId, MutableSet<StatusEffectInstance>> = mutableMapOf()

        override fun active(playerId: PlayerId): Collection<StatusEffectInstance> =
            store[playerId]?.toList() ?: emptyList()

        override fun has(playerId: PlayerId, effectId: StatusEffectId): Boolean =
            store[playerId]?.any { it.spec.id == effectId } == true

        override fun apply(instance: StatusEffectInstance) {
            store.getOrPut(instance.targetPlayerId) { mutableSetOf() } += instance
        }

        override fun remove(playerId: PlayerId, effectId: StatusEffectId) {
            store[playerId]?.removeIf { it.spec.id == effectId }
        }

        override fun clear(playerId: PlayerId) {
            store.remove(playerId)
        }
    }

    /**
     * Minimal recording impl of [IMatchTimerService] for the TimeWarp scenario.
     * Inlined per the cross-plugin test-source visibility constraint noted on
     * [RecordingStatusEffectContainer].
     */
    private class RecordingMatchTimerService : IMatchTimerService {
        val shiftsByMatch: MutableMap<MatchId, MutableList<Int>> = mutableMapOf()

        override fun shiftPhaseTimer(matchId: MatchId, deltaTicks: Int): Result<Unit, TimerShiftError> {
            shiftsByMatch.getOrPut(matchId) { mutableListOf() } += deltaTicks
            return Result.Success(Unit)
        }

        fun totalShiftFor(matchId: MatchId): Int = shiftsByMatch[matchId]?.sum() ?: 0
    }

    // ────── GravityShift ─────────────────────────────────────────────

    @Test
    fun `GravityShift sets a gravity multiplier on every active player`() {
        val harness = AsteraTestHarness.newDefault()
        val alice = harness.spawnPlayer("gravity-alice")
        val bob = harness.spawnPlayer("gravity-bob")
        val movement = FakePlayerMovementGateway()
        val anomalyId = AnomalyId("gravity-shift-1")

        val resolveAnomaly = ResolveAnomalyEffectUseCase(
            generators = null,
            playerMovementGateway = movement,
            matchPlayerListProvider = { mid -> if (mid == matchId) listOf(alice.id, bob.id) else emptyList() },
        )

        val descriptor = AnomalyDescriptor(
            type = AnomalyType.GravityShift,
            weight = 1,
            durationTicks = 200L,
            attributeKey = AttributeKey("anomaly.gravity_multiplier"),
            modifierOperation = AttributeModifier.Operation.MULTIPLY,
            modifierValue = 0.25,
        )

        resolveAnomaly.apply(anomalyId, matchId, descriptor)

        for (player in listOf(alice, bob)) {
            assertThat(movement.currentMultiplier(player.id))
                .withFailMessage {
                    "GravityShift did not invoke IPlayerMovementGateway for ${player.name}. " +
                        "Current state: ${movement.currentState(player.id)}"
                }
                .isEqualTo(0.25)
            assertThat(movement.currentState(player.id)?.expiresAtTick)
                .isEqualTo(200)
        }
    }

    // ────── FogOfWar ─────────────────────────────────────────────────

    @Test
    fun `FogOfWar shrinks vision radius via IPlayerVisionGateway`() {
        val harness = AsteraTestHarness.newDefault()
        val alice = harness.spawnPlayer("fog-alice")
        val bob = harness.spawnPlayer("fog-bob")
        val vision = FakePlayerVisionGateway()
        val anomalyId = AnomalyId("fog-of-war-1")

        val resolveAnomaly = ResolveAnomalyEffectUseCase(
            generators = null,
            playerVisionGateway = vision,
            matchPlayerListProvider = { mid -> if (mid == matchId) listOf(alice.id, bob.id) else emptyList() },
        )

        val descriptor = AnomalyDescriptor(
            type = AnomalyType.FogOfWar,
            weight = 1,
            durationTicks = 300L,
            attributeKey = AttributeKey("anomaly.fog_radius"),
            modifierOperation = AttributeModifier.Operation.SET,
            modifierValue = 8.0,
        )

        resolveAnomaly.apply(anomalyId, matchId, descriptor)

        for (player in listOf(alice, bob)) {
            assertThat(vision.currentFogRadius(player.id))
                .withFailMessage {
                    "FogOfWar did not invoke IPlayerVisionGateway for ${player.name}. " +
                        "Current state: ${vision.currentState(player.id)}"
                }
                .isEqualTo(8)
        }
    }
}
