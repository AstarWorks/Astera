package com.astarworks.astera.match.application.usecase.anomaly

import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.core.domain.status.StatusEffectId
import com.astarworks.astera.combat.domain.status.StatusEffectInstance
import com.astarworks.astera.core.application.service.InMemoryPlayerAttributeContainer
import com.astarworks.astera.core.domain.attribute.AttributeKey
import com.astarworks.astera.core.domain.attribute.AttributeModifier
import com.astarworks.astera.core.domain.attribute.SimpleAttributeContainer
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.service.InMemoryGeneratorRepository
import com.astarworks.astera.match.domain.anomaly.AnomalyDescriptor
import com.astarworks.astera.match.domain.anomaly.AnomalyId
import com.astarworks.astera.match.domain.anomaly.AnomalyType
import com.astarworks.astera.match.domain.generator.Affiliation
import com.astarworks.astera.match.domain.generator.GeneratorAttributes
import com.astarworks.astera.match.domain.generator.GeneratorId
import com.astarworks.astera.match.domain.generator.GeneratorTier
import com.astarworks.astera.match.domain.generator.StarGenerator
import com.astarworks.astera.match.domain.player.PlayerMatchAttributes
import com.astarworks.astera.match.testfixtures.FakeMatchTimerService
import com.astarworks.astera.testfixtures.FakePlayerMovementGateway
import com.astarworks.astera.testfixtures.FakePlayerVisionGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * [ResolveAnomalyEffectUseCase] tests covering all 6 [AnomalyType] variants.
 *
 * - **ResourceSpike** (Wave 5c): generator-modifier apply / expire / scope.
 * - **StatusBurst** (Wave 5g partial wire): the dependency on
 *   `IStatusEffectContainer` is wired but the data path (descriptor →
 *   concrete `StatusEffectInstance` collection) lands in Wave 6a per
 *   ADR-0072. The test pins that the use case calls
 *   `applyBulk(emptyList())` (no side effects) and never accidentally
 *   touches generator modifiers — i.e. the partial wire is a clean no-op,
 *   not a silent regression on Wave 5c's generator-only contract.
 * - **SpawnSurge** (Wave 5g): per-player `RESPAWN_RATE_MULTIPLIER` modifier
 *   via `IPlayerAttributeContainerStore`; expire round-trips the modifier.
 * - **TimeWarp** (Wave 5g): `IMatchTimerService.shiftPhaseTimer` invoked
 *   with the descriptor's signed delta (`durationTicks * modifierValue`).
 * - **GravityShift** (Wave 5g): per-player `IPlayerMovementGateway.setGravityMultiplier`
 *   with TTL = `durationTicks`.
 * - **FogOfWar** (Wave 5g): per-player `IPlayerVisionGateway.setFogRadius`
 *   with TTL = `durationTicks`. Whether the Wave 6a Paper adapter chooses
 *   to implement the vision shrink by also applying a Bukkit `BLINDNESS`
 *   potion is a Paper-side decision — the domain only knows "shrink fog
 *   radius" (ADR-0072 §3: Vanilla potion unification is closed to the
 *   Wave 6a `PaperStatusEffectAdapter` merge layer).
 */
internal class ResolveAnomalyEffectUseCaseTest {

    private lateinit var generators: InMemoryGeneratorRepository
    private lateinit var useCase: ResolveAnomalyEffectUseCase

    private val matchId = MatchId("resolve-test-match")
    private val otherMatchId = MatchId("other-match")
    private val anomalyId = AnomalyId("anom-1")

    private val resourceSpikeDescriptor = AnomalyDescriptor(
        type = AnomalyType.ResourceSpike,
        weight = 5,
        durationTicks = 600L,
        attributeKey = GeneratorAttributes.PERIOD_MULTIPLIER,
        modifierOperation = AttributeModifier.Operation.MULTIPLY,
        modifierValue = 0.5,
    )

    @BeforeEach
    fun setUp() {
        generators = InMemoryGeneratorRepository()
        useCase = ResolveAnomalyEffectUseCase(generators)
        generators.save(makeGenerator("gen-a", matchId))
        generators.save(makeGenerator("gen-b", matchId))
        generators.save(makeGenerator("gen-other", otherMatchId))
    }

    // ─── ResourceSpike (Wave 5c pins) ─────────────────────────────────

    @Test
    fun `apply ResourceSpike adds a MULTIPLY modifier to every generator in the match`() {
        useCase.apply(anomalyId, matchId, resourceSpikeDescriptor)

        for (gen in generators.listForMatch(matchId)) {
            val mods = gen.attributes.modifiers()
            assertThat(mods).hasSize(1)
            val mod = mods.single()
            assertThat(mod.key).isEqualTo(GeneratorAttributes.PERIOD_MULTIPLIER)
            assertThat(mod.operation).isEqualTo(AttributeModifier.Operation.MULTIPLY)
            assertThat(mod.value).isEqualTo(0.5)
            assertThat(mod.sourceId).isEqualTo("anomaly:anom-1")
        }
    }

    @Test
    fun `apply ResourceSpike does not touch generators in other matches`() {
        useCase.apply(anomalyId, matchId, resourceSpikeDescriptor)

        val otherGen = generators.findById(GeneratorId("gen-other"))!!
        assertThat(otherGen.attributes.modifiers()).isEmpty()
    }

    @Test
    fun `expire removes only modifiers with the matching sourceId`() {
        for (gen in generators.listForMatch(matchId)) {
            gen.attributes.addModifier(
                AttributeModifier(
                    key = GeneratorAttributes.PERIOD_MULTIPLIER,
                    operation = AttributeModifier.Operation.MULTIPLY,
                    value = 0.75,
                    sourceId = "sudden-death:other",
                ),
            )
        }

        useCase.apply(anomalyId, matchId, resourceSpikeDescriptor)
        useCase.expire(anomalyId, matchId)

        for (gen in generators.listForMatch(matchId)) {
            val remaining = gen.attributes.modifiers()
            assertThat(remaining).hasSize(1)
            assertThat(remaining.single().sourceId).isEqualTo("sudden-death:other")
        }
    }

    @Test
    fun `expire is a no-op when the generators repository is null`() {
        val useCaseWithoutRepo = ResolveAnomalyEffectUseCase(generators = null)

        useCase.apply(anomalyId, matchId, resourceSpikeDescriptor)
        useCaseWithoutRepo.expire(anomalyId, matchId)

        for (gen in generators.listForMatch(matchId)) {
            assertThat(gen.attributes.modifiers()).hasSize(1)
        }
    }

    @Test
    fun `apply is a no-op when the generators repository is null`() {
        val useCaseWithoutRepo = ResolveAnomalyEffectUseCase(generators = null)

        useCaseWithoutRepo.apply(anomalyId, matchId, resourceSpikeDescriptor)

        for (gen in generators.listForMatch(matchId)) {
            assertThat(gen.attributes.modifiers()).isEmpty()
        }
    }

    // ─── Wave 5g variants ─────────────────────────────────────────────

    @Test
    fun `apply StatusBurst is a clean no-op until Wave 6a wires content data`() {
        // Wave 5g, ADR-0072: the IStatusEffectContainer dependency is committed
        // but the data path (descriptor → concrete StatusEffectInstance list)
        // lands in Wave 6a with the content-driven anomaly→effect mapping.
        // The partial wire calls applyBulk(emptyList()) which is a no-op;
        // crucially it must not accidentally touch generator modifiers.
        val statusContainer = RecordingStatusEffectContainer()
        val players = listOf(playerId("alice"), playerId("bob"))
        val useCase = ResolveAnomalyEffectUseCase(
            generators = generators,
            statusEffectContainer = statusContainer,
            matchPlayerListProvider = { mid -> if (mid == matchId) players else emptyList() },
        )
        val descriptor = resourceSpikeDescriptor.copy(
            type = AnomalyType.StatusBurst,
            attributeKey = AttributeKey("anomaly.status_intensity"),
        )

        useCase.apply(anomalyId, matchId, descriptor)

        for (player in players) {
            assertThat(statusContainer.active(player)).isEmpty()
        }
        for (gen in generators.listForMatch(matchId)) {
            assertThat(gen.attributes.modifiers()).isEmpty()
        }
    }

    @Test
    fun `apply SpawnSurge writes RESPAWN_RATE_MULTIPLIER modifier on every player container`() {
        val store = InMemoryPlayerAttributeContainer()
        val players = listOf(playerId("carol"), playerId("dan"))
        val useCase = ResolveAnomalyEffectUseCase(
            generators = generators,
            playerAttributeStore = store,
            matchPlayerListProvider = { mid -> if (mid == matchId) players else emptyList() },
        )
        val descriptor = AnomalyDescriptor(
            type = AnomalyType.SpawnSurge,
            weight = 5,
            durationTicks = 200L,
            attributeKey = AttributeKey("anomaly.spawn_rate"),
            modifierOperation = AttributeModifier.Operation.MULTIPLY,
            modifierValue = 2.0,
        )

        useCase.apply(anomalyId, matchId, descriptor)

        for (player in players) {
            val mods = store.containerFor(player).modifiers()
            assertThat(mods).hasSize(1)
            val mod = mods.single()
            assertThat(mod.key).isEqualTo(PlayerMatchAttributes.RESPAWN_RATE_MULTIPLIER)
            assertThat(mod.operation).isEqualTo(AttributeModifier.Operation.MULTIPLY)
            assertThat(mod.value).isEqualTo(2.0)
            assertThat(mod.sourceId).isEqualTo("anomaly:anom-1")
        }

        useCase.expire(anomalyId, matchId)
        for (player in players) {
            assertThat(store.containerFor(player).modifiers()).isEmpty()
        }
    }

    @Test
    fun `apply TimeWarp shifts the phase timer by signed delta`() {
        val timer = FakeMatchTimerService()
        val useCase = ResolveAnomalyEffectUseCase(
            generators = generators,
            matchTimerService = timer,
        )
        val descriptor = AnomalyDescriptor(
            type = AnomalyType.TimeWarp,
            weight = 5,
            durationTicks = 200L,
            attributeKey = AttributeKey("anomaly.time_warp_factor"),
            modifierOperation = AttributeModifier.Operation.MULTIPLY,
            modifierValue = 1.5,
        )

        useCase.apply(anomalyId, matchId, descriptor)

        assertThat(timer.shiftsByMatch[matchId]).containsExactly(300)
    }

    @Test
    fun `apply GravityShift sets gravity multiplier with descriptor TTL for every player`() {
        val movement = FakePlayerMovementGateway()
        val players = listOf(playerId("eve"), playerId("frank"))
        val useCase = ResolveAnomalyEffectUseCase(
            generators = generators,
            playerMovementGateway = movement,
            matchPlayerListProvider = { mid -> if (mid == matchId) players else emptyList() },
        )
        val descriptor = AnomalyDescriptor(
            type = AnomalyType.GravityShift,
            weight = 5,
            durationTicks = 400L,
            attributeKey = AttributeKey("anomaly.gravity_factor"),
            modifierOperation = AttributeModifier.Operation.MULTIPLY,
            modifierValue = 0.5,
        )

        useCase.apply(anomalyId, matchId, descriptor)

        for (player in players) {
            val state = movement.currentState(player)
            assertThat(state).isNotNull
            assertThat(state!!.multiplier).isEqualTo(0.5)
            assertThat(state.expiresAtTick).isEqualTo(400)
        }
    }

    @Test
    fun `apply FogOfWar sets fog radius for every player via IPlayerVisionGateway`() {
        // ADR-0072: domain only knows "shrink fog radius". Whether the Wave 6a
        // Paper adapter implements this by applying Bukkit BLINDNESS, shrinking
        // render distance, or both, is a Paper-side decision — no vanilla
        // potion surface is visible to the domain.
        val vision = FakePlayerVisionGateway()
        val players = listOf(playerId("gina"), playerId("henry"))
        val useCase = ResolveAnomalyEffectUseCase(
            generators = generators,
            playerVisionGateway = vision,
            matchPlayerListProvider = { mid -> if (mid == matchId) players else emptyList() },
        )
        val descriptor = AnomalyDescriptor(
            type = AnomalyType.FogOfWar,
            weight = 5,
            durationTicks = 300L,
            attributeKey = AttributeKey("anomaly.visibility_reduction"),
            modifierOperation = AttributeModifier.Operation.ADD,
            modifierValue = 8.0,
        )

        useCase.apply(anomalyId, matchId, descriptor)

        for (player in players) {
            assertThat(vision.currentFogRadius(player)).isEqualTo(8)
        }
    }

    private fun playerId(seed: String): PlayerId =
        PlayerId(UUID.nameUUIDFromBytes(seed.toByteArray()))

    /**
     * Minimal recording impl of [IStatusEffectContainer] for the StatusBurst
     * partial-wire test. Inlined here rather than imported from
     * `combat.testfixtures` because Gradle does not expose another module's
     * test source set without the `java-test-fixtures` plugin (Wave 5g
     * intentionally does not adopt it — overkill for one Fake per port).
     */
    private class RecordingStatusEffectContainer : IStatusEffectContainer {
        private val store: MutableMap<PlayerId, MutableSet<StatusEffectInstance>> = mutableMapOf()
        val bulkApplyCalls: MutableList<Int> = mutableListOf()

        override fun active(playerId: PlayerId): Collection<StatusEffectInstance> =
            store[playerId]?.toList() ?: emptyList()

        override fun has(playerId: PlayerId, effectId: StatusEffectId): Boolean =
            store[playerId]?.any { it.spec.id == effectId } == true

        override fun apply(instance: StatusEffectInstance) {
            store.getOrPut(instance.targetPlayerId) { mutableSetOf() } += instance
        }

        override fun applyBulk(instances: Collection<StatusEffectInstance>) {
            bulkApplyCalls += instances.size
            instances.forEach { apply(it) }
        }

        override fun remove(playerId: PlayerId, effectId: StatusEffectId) {
            store[playerId]?.removeIf { it.spec.id == effectId }
        }

        override fun clear(playerId: PlayerId) {
            store.remove(playerId)
        }
    }

    private fun makeGenerator(id: String, matchId: MatchId): StarGenerator = StarGenerator(
        id = GeneratorId(id),
        matchId = matchId,
        position = Vec3(0.0, 64.0, 0.0),
        currency = Currency.STAR,
        period = 30.seconds,
        amount = 1,
        tier = GeneratorTier.Iron,
        affiliation = Affiliation.Neutral,
        attributes = SimpleAttributeContainer(),
    )
}
