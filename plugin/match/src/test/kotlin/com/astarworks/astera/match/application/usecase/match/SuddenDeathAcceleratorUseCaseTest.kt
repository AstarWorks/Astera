package com.astarworks.astera.match.application.usecase.match

import com.astarworks.astera.core.domain.attribute.AttributeModifier
import com.astarworks.astera.core.domain.attribute.SimpleAttributeContainer
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.match.application.service.InMemoryGeneratorRepository
import com.astarworks.astera.match.domain.event.PhaseChanged
import com.astarworks.astera.match.domain.generator.Affiliation
import com.astarworks.astera.match.domain.generator.GeneratorAttributes
import com.astarworks.astera.match.domain.generator.GeneratorId
import com.astarworks.astera.match.domain.generator.GeneratorTier
import com.astarworks.astera.match.domain.generator.StarGenerator
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Wave 5c [SuddenDeathAcceleratorUseCase] tests.
 *
 * Pins the contract:
 *  - on `ACTIVE → SUDDEN_DEATH`, every generator in the match receives a
 *    MULTIPLY 0.5 modifier on `generator.period_multiplier` tagged
 *    `sudden-death:<matchId>`.
 *  - on `SUDDEN_DEATH → ENDED` (or any other transition leaving
 *    SUDDEN_DEATH), the modifier is removed.
 *  - transitions that neither enter nor leave SUDDEN_DEATH are no-ops.
 *  - subscription lifecycle (active / cancellable).
 */
internal class SuddenDeathAcceleratorUseCaseTest {

    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var generators: InMemoryGeneratorRepository
    private lateinit var useCase: SuddenDeathAcceleratorUseCase

    private val matchId = MatchId("sd-test-match")
    private val otherMatchId = MatchId("sd-other-match")

    @BeforeEach
    fun setUp() {
        eventBus = FakeDomainEventBus()
        generators = InMemoryGeneratorRepository().apply {
            save(makeGenerator("gen-a", matchId))
            save(makeGenerator("gen-b", matchId))
            save(makeGenerator("gen-other", otherMatchId))
        }
        useCase = SuddenDeathAcceleratorUseCase(eventBus, generators)
        useCase.subscribe()
    }

    @Test
    fun `entering SUDDEN_DEATH adds a MULTIPLY 0_5 modifier with sudden-death sourceId`() {
        eventBus.publish(
            PhaseChanged(
                matchId = matchId,
                from = MatchPhase.ACTIVE,
                to = MatchPhase.SUDDEN_DEATH,
                atTick = 100L,
            ),
        )

        for (gen in generators.listForMatch(matchId)) {
            val mods = gen.attributes.modifiers()
            assertThat(mods).hasSize(1)
            val mod = mods.single()
            assertThat(mod.key).isEqualTo(GeneratorAttributes.PERIOD_MULTIPLIER)
            assertThat(mod.operation).isEqualTo(AttributeModifier.Operation.MULTIPLY)
            assertThat(mod.value).isEqualTo(SuddenDeathAcceleratorUseCase.ACCELERATION_FACTOR)
            assertThat(mod.value).isEqualTo(0.5)
            assertThat(mod.sourceId).isEqualTo("sudden-death:sd-test-match")
        }
    }

    @Test
    fun `entering SUDDEN_DEATH does not affect generators in other matches`() {
        eventBus.publish(
            PhaseChanged(
                matchId = matchId,
                from = MatchPhase.ACTIVE,
                to = MatchPhase.SUDDEN_DEATH,
                atTick = 100L,
            ),
        )

        val other = generators.findById(GeneratorId("gen-other"))!!
        assertThat(other.attributes.modifiers()).isEmpty()
    }

    @Test
    fun `leaving SUDDEN_DEATH to ENDED removes the modifier`() {
        eventBus.publish(
            PhaseChanged(
                matchId = matchId,
                from = MatchPhase.ACTIVE,
                to = MatchPhase.SUDDEN_DEATH,
                atTick = 100L,
            ),
        )
        eventBus.publish(
            PhaseChanged(
                matchId = matchId,
                from = MatchPhase.SUDDEN_DEATH,
                to = MatchPhase.ENDED,
                atTick = 200L,
            ),
        )

        for (gen in generators.listForMatch(matchId)) {
            assertThat(gen.attributes.modifiers()).isEmpty()
        }
    }

    @Test
    fun `transitions that do not touch SUDDEN_DEATH are no-ops`() {
        eventBus.publish(
            PhaseChanged(
                matchId = matchId,
                from = MatchPhase.COUNTDOWN,
                to = MatchPhase.ACTIVE,
                atTick = 50L,
            ),
        )

        for (gen in generators.listForMatch(matchId)) {
            assertThat(gen.attributes.modifiers()).isEmpty()
        }
    }

    @Test
    fun `removal preserves unrelated modifiers`() {
        // Seed a modifier from another source we must not touch.
        for (gen in generators.listForMatch(matchId)) {
            gen.attributes.addModifier(
                AttributeModifier(
                    key = GeneratorAttributes.PERIOD_MULTIPLIER,
                    operation = AttributeModifier.Operation.MULTIPLY,
                    value = 0.8,
                    sourceId = "anomaly:resource-spike-x",
                ),
            )
        }

        eventBus.publish(
            PhaseChanged(
                matchId = matchId,
                from = MatchPhase.ACTIVE,
                to = MatchPhase.SUDDEN_DEATH,
                atTick = 100L,
            ),
        )
        eventBus.publish(
            PhaseChanged(
                matchId = matchId,
                from = MatchPhase.SUDDEN_DEATH,
                to = MatchPhase.ENDED,
                atTick = 200L,
            ),
        )

        for (gen in generators.listForMatch(matchId)) {
            val remaining = gen.attributes.modifiers()
            assertThat(remaining).hasSize(1)
            assertThat(remaining.single().sourceId).isEqualTo("anomaly:resource-spike-x")
        }
    }

    @Test
    fun `subscribe returns an active subscription that can be cancelled`() {
        // Use a fresh use case so we control the lifecycle without setUp's subscription.
        val freshUseCase = SuddenDeathAcceleratorUseCase(FakeDomainEventBus(), generators)
        val sub = freshUseCase.subscribe()
        assertThat(sub.isActive).isTrue()

        sub.cancel()
        assertThat(sub.isActive).isFalse()
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
