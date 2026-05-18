package com.astarworks.astera.match.domain.generator

import com.astarworks.astera.core.domain.attribute.SimpleAttributeContainer
import com.astarworks.astera.core.domain.attribute.number
import com.astarworks.astera.core.domain.attribute.setNumber
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class StarGeneratorTest {

    @Test
    fun `construction preserves field values`() {
        val container = SimpleAttributeContainer()
        val generator = StarGenerator(
            id = GeneratorId("arena-center"),
            matchId = MatchId("match-x1"),
            position = Vec3(1.0, 64.0, -2.0),
            currency = Currency.STAR,
            period = 30.seconds,
            amount = 1,
            tier = GeneratorTier.Iron,
            affiliation = Affiliation.Neutral,
            attributes = container,
        )

        assertThat(generator.id.value).isEqualTo("arena-center")
        assertThat(generator.matchId.value).isEqualTo("match-x1")
        assertThat(generator.position).isEqualTo(Vec3(1.0, 64.0, -2.0))
        assertThat(generator.currency).isEqualTo(Currency.STAR)
        assertThat(generator.period).isEqualTo(30.seconds)
        assertThat(generator.amount).isEqualTo(1)
        assertThat(generator.tier).isEqualTo(GeneratorTier.Iron)
        assertThat(generator.affiliation).isEqualTo(Affiliation.Neutral)
        assertThat(generator.attributes).isSameAs(container)
    }

    @Test
    fun `attribute container supports energy read-write through the generator`() {
        val generator = neutralGenerator()

        // Default read: no value stored
        assertThat(generator.attributes.number(GeneratorAttributes.ENERGY)).isNull()

        // Write
        generator.attributes.setNumber(GeneratorAttributes.ENERGY, 12.5)
        assertThat(generator.attributes.number(GeneratorAttributes.ENERGY)).isEqualTo(12.5)

        // Overwrite
        generator.attributes.setNumber(GeneratorAttributes.ENERGY, 600.0)
        assertThat(generator.attributes.number(GeneratorAttributes.ENERGY)).isEqualTo(600.0)
    }

    @Test
    fun `amount below 1 is rejected at construction`() {
        assertThrows<IllegalArgumentException> {
            StarGenerator(
                id = GeneratorId("bad"),
                matchId = MatchId("m"),
                position = Vec3.ZERO,
                currency = Currency.STAR,
                period = 1.seconds,
                amount = 0,
                tier = GeneratorTier.Iron,
                affiliation = Affiliation.Neutral,
                attributes = SimpleAttributeContainer(),
            )
        }
    }

    @Test
    fun `non-positive period is rejected at construction`() {
        assertThrows<IllegalArgumentException> {
            StarGenerator(
                id = GeneratorId("bad"),
                matchId = MatchId("m"),
                position = Vec3.ZERO,
                currency = Currency.STAR,
                period = Duration.ZERO,
                amount = 1,
                tier = GeneratorTier.Iron,
                affiliation = Affiliation.Neutral,
                attributes = SimpleAttributeContainer(),
            )
        }
    }

    private fun neutralGenerator(): StarGenerator = StarGenerator(
        id = GeneratorId("arena-test"),
        matchId = MatchId("match-x1"),
        position = Vec3.ZERO,
        currency = Currency.STAR,
        period = 30.seconds,
        amount = 1,
        tier = GeneratorTier.Iron,
        affiliation = Affiliation.Neutral,
        attributes = SimpleAttributeContainer(),
    )
}
