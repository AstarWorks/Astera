package com.astarworks.astera.core.application.config

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.item.ItemId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StarterGrantYamlConfigTest {

    @Test
    fun `toDomain returns parsed starter grant on the happy path`() {
        val dto = StarterGrantYamlConfig(
            defaultLoadout = listOf("example-sword", "example-wand"),
            walletGrants = mapOf("GOLD" to 100L, "STAR" to 0L),
            tutorial = StarterGrantYamlConfig.TutorialBlock(
                autoEnroll = true,
                scriptId = "getting-started",
            ),
            welcomeDialogId = "astera-welcome",
        )

        val result = dto.toDomain()

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val grant = (result as Result.Success).value
        assertThat(grant.defaultLoadout)
            .containsExactly(ItemId("example-sword"), ItemId("example-wand"))
        // Zero entries are dropped to keep the audit log clean.
        assertThat(grant.walletGrants).containsOnly(java.util.Map.entry(Currency.GOLD, 100L))
        assertThat(grant.tutorialAutoEnroll).isTrue
        assertThat(grant.tutorialScriptId).isEqualTo("getting-started")
        assertThat(grant.welcomeDialogId).isEqualTo("astera-welcome")
    }

    @Test
    fun `toDomain rejects an unknown currency key`() {
        val dto = StarterGrantYamlConfig(
            walletGrants = mapOf("DOGECOIN" to 50L),
        )

        val result = dto.toDomain()

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val err = (result as Result.Failure).error
        assertThat(err).isInstanceOf(ConfigError.UnknownEnumValue::class.java)
        assertThat((err as ConfigError.UnknownEnumValue).field).isEqualTo("wallet_grants.DOGECOIN")
        assertThat(err.accepted).contains("GOLD", "STAR", "GEM")
    }

    @Test
    fun `toDomain rejects a negative wallet amount`() {
        val dto = StarterGrantYamlConfig(
            walletGrants = mapOf("GOLD" to -5L),
        )

        val result = dto.toDomain()

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val err = (result as Result.Failure).error
        assertThat(err).isInstanceOf(ConfigError.OutOfRange::class.java)
        assertThat((err as ConfigError.OutOfRange).field).isEqualTo("wallet_grants.GOLD")
    }

    @Test
    fun `toDomain rejects a malformed item id`() {
        val dto = StarterGrantYamlConfig(
            defaultLoadout = listOf("UpperCaseBad"),
        )

        val result = dto.toDomain()

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val err = (result as Result.Failure).error
        assertThat(err).isInstanceOf(ConfigError.InvalidIdentifier::class.java)
        assertThat((err as ConfigError.InvalidIdentifier).field).isEqualTo("default_loadout[0]")
    }

    @Test
    fun `toDomain rejects auto_enroll true with no script_id`() {
        val dto = StarterGrantYamlConfig(
            tutorial = StarterGrantYamlConfig.TutorialBlock(autoEnroll = true, scriptId = null),
        )

        val result = dto.toDomain()

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val err = (result as Result.Failure).error
        assertThat(err).isInstanceOf(ConfigError.FieldShapeMismatch::class.java)
        assertThat((err as ConfigError.FieldShapeMismatch).field).isEqualTo("tutorial.script_id")
    }

    @Test
    fun `toDomain clears welcome dialog when blank`() {
        val dto = StarterGrantYamlConfig(welcomeDialogId = "   ")

        val result = dto.toDomain()

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat((result as Result.Success).value.welcomeDialogId).isNull()
    }

    @Test
    fun `toDomain returns empty grant for an empty DTO`() {
        val result = StarterGrantYamlConfig().toDomain()

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val grant = (result as Result.Success).value
        assertThat(grant.defaultLoadout).isEmpty()
        assertThat(grant.walletGrants).isEmpty()
        assertThat(grant.tutorialAutoEnroll).isFalse
        assertThat(grant.welcomeDialogId).isNull()
    }
}
