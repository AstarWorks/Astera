package com.astarworks.astera.match.application.config

import com.astarworks.astera.core.application.config.ConfigError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.attribute.AttributeValue
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.domain.generator.Affiliation
import com.astarworks.astera.match.domain.generator.GeneratorAttributes
import com.astarworks.astera.match.domain.generator.GeneratorTier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pins the `Result<T, ConfigError>` contract for [GeneratorYamlConfig.toDomain].
 *
 * The DTO previously threw `IllegalArgumentException` directly — see the
 * cleanup commit that introduced this file. Production loaders are now
 * obliged to handle the typed failures.
 */
internal class GeneratorYamlConfigTest {

    private fun validDto() = GeneratorYamlConfig(
        id = "arena-center",
        matchStageId = "example-arena",
        position = Vec3YamlConfig(0.0, 64.0, 0.0),
        currency = "STAR",
        periodSeconds = 30.0,
        amount = 1,
        tier = "iron",
        affiliation = AffiliationYamlConfig(kind = "neutral"),
    )

    @Test
    fun `valid DTO converts to StarGenerator with seeded multipliers`() {
        val result = validDto().toDomain(MatchId("match-x"))

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val gen = (result as Result.Success).value
        assertThat(gen.currency).isEqualTo(Currency.STAR)
        assertThat(gen.tier).isEqualTo(GeneratorTier.Iron)
        assertThat(gen.affiliation).isEqualTo(Affiliation.Neutral)
        assertThat(gen.attributes.get(GeneratorAttributes.PERIOD_MULTIPLIER))
            .isEqualTo(AttributeValue.NumberValue(1.0))
        assertThat(gen.attributes.get(GeneratorAttributes.AMOUNT_MULTIPLIER))
            .isEqualTo(AttributeValue.NumberValue(1.0))
    }

    @Test
    fun `unknown currency returns UnknownEnumValue failure`() {
        val result = validDto().copy(currency = "BTC").toDomain(MatchId("match-x"))

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val err = (result as Result.Failure).error
        assertThat(err).isInstanceOf(ConfigError.UnknownEnumValue::class.java)
        err as ConfigError.UnknownEnumValue
        assertThat(err.field).isEqualTo("currency")
        assertThat(err.rawValue).isEqualTo("BTC")
        assertThat(err.accepted).contains("STAR")
    }

    @Test
    fun `unknown tier returns UnknownEnumValue failure`() {
        val result = validDto().copy(tier = "platinum").toDomain(MatchId("match-x"))

        val err = (result as Result.Failure).error as ConfigError.UnknownEnumValue
        assertThat(err.field).isEqualTo("tier")
        assertThat(err.rawValue).isEqualTo("platinum")
        assertThat(err.accepted).containsExactly("iron", "gold", "emerald", "diamond")
    }

    @Test
    fun `affiliation neutral with team_id returns FieldShapeMismatch`() {
        val dto = validDto().copy(
            affiliation = AffiliationYamlConfig(kind = "neutral", teamId = "red"),
        )

        val err = (dto.toDomain(MatchId("match-x")) as Result.Failure)
            .error as ConfigError.FieldShapeMismatch
        assertThat(err.field).isEqualTo("affiliation.team_id")
        assertThat(err.detailMessage).contains("neutral").contains("must not carry team_id")
    }

    @Test
    fun `affiliation team without team_id returns FieldShapeMismatch`() {
        val dto = validDto().copy(
            affiliation = AffiliationYamlConfig(kind = "team", teamId = null),
        )

        val err = (dto.toDomain(MatchId("match-x")) as Result.Failure)
            .error as ConfigError.FieldShapeMismatch
        assertThat(err.field).isEqualTo("affiliation.team_id")
        assertThat(err.detailMessage).contains("requires team_id")
    }

    @Test
    fun `affiliation team with valid team_id succeeds`() {
        val dto = validDto().copy(
            affiliation = AffiliationYamlConfig(kind = "team", teamId = "red"),
        )

        val gen = (dto.toDomain(MatchId("match-x")) as Result.Success).value
        assertThat(gen.affiliation).isInstanceOf(Affiliation.Team::class.java)
    }
}
