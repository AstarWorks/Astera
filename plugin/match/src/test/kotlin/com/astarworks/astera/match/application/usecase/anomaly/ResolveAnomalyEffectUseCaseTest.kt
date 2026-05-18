package com.astarworks.astera.match.application.usecase.anomaly

import com.astarworks.astera.core.domain.attribute.AttributeKey
import com.astarworks.astera.core.domain.attribute.AttributeModifier
import com.astarworks.astera.core.domain.attribute.SimpleAttributeContainer
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.application.service.InMemoryGeneratorRepository
import com.astarworks.astera.match.domain.anomaly.AnomalyDescriptor
import com.astarworks.astera.match.domain.anomaly.AnomalyId
import com.astarworks.astera.match.domain.anomaly.AnomalyType
import com.astarworks.astera.match.domain.generator.Affiliation
import com.astarworks.astera.match.domain.generator.GeneratorAttributes
import com.astarworks.astera.match.domain.generator.GeneratorId
import com.astarworks.astera.match.domain.generator.GeneratorTier
import com.astarworks.astera.match.domain.generator.StarGenerator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Wave 5c [ResolveAnomalyEffectUseCase] tests.
 *
 * Pins:
 *  - `apply(ResourceSpike, ...)` writes a modifier on every generator in the
 *    match using the descriptor's [AttributeModifier.Operation] + value,
 *    tagged with `anomaly:<anomalyId>`.
 *  - Generators belonging to OTHER matches are left untouched.
 *  - `expire(...)` removes exactly the modifiers tagged with the matching
 *    sourceId, leaving any unrelated modifier in place.
 *  - Non-ResourceSpike types currently no-op (Wave 6 placeholders).
 *  - With a null [com.astarworks.astera.match.application.port.outbound.IGeneratorRepository]
 *    the use case is a complete no-op (test wiring convenience).
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
        // Two generators in our match…
        generators.save(makeGenerator("gen-a", matchId))
        generators.save(makeGenerator("gen-b", matchId))
        // …and one in a sibling match that must stay untouched.
        generators.save(makeGenerator("gen-other", otherMatchId))
    }

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
        // Seed each generator with a foreign modifier we must not remove.
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
    fun `apply for non-ResourceSpike types currently no-ops (Wave 6 placeholder)`() {
        val statusBurst = resourceSpikeDescriptor.copy(
            type = AnomalyType.StatusBurst,
            attributeKey = AttributeKey("anomaly.status_intensity"),
        )

        useCase.apply(anomalyId, matchId, statusBurst)

        for (gen in generators.listForMatch(matchId)) {
            assertThat(gen.attributes.modifiers()).isEmpty()
        }
    }

    @Test
    fun `expire is a no-op when the generators repository is null`() {
        val useCaseWithoutRepo = ResolveAnomalyEffectUseCase(generators = null)

        // Apply via the real use case to seed modifiers…
        useCase.apply(anomalyId, matchId, resourceSpikeDescriptor)
        // …then expire via the null-repo instance: it must not crash.
        useCaseWithoutRepo.expire(anomalyId, matchId)

        // Real use case's modifiers are still present (null instance did nothing).
        for (gen in generators.listForMatch(matchId)) {
            assertThat(gen.attributes.modifiers()).hasSize(1)
        }
    }

    @Test
    fun `apply is a no-op when the generators repository is null`() {
        val useCaseWithoutRepo = ResolveAnomalyEffectUseCase(generators = null)

        useCaseWithoutRepo.apply(anomalyId, matchId, resourceSpikeDescriptor)

        // Surface mutation check via the directly-held repository.
        for (gen in generators.listForMatch(matchId)) {
            assertThat(gen.attributes.modifiers()).isEmpty()
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
