package com.astarworks.astera.core.domain.player

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.item.ItemId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class StarterGrantTest {

    @Test
    fun `EMPTY has no loadout no wallet no tutorial`() {
        assertThat(StarterGrant.EMPTY.defaultLoadout).isEmpty()
        assertThat(StarterGrant.EMPTY.walletGrants).isEmpty()
        assertThat(StarterGrant.EMPTY.tutorialAutoEnroll).isFalse
        assertThat(StarterGrant.EMPTY.tutorialScriptId).isNull()
        assertThat(StarterGrant.EMPTY.welcomeDialogId).isNull()
    }

    @Test
    fun `init rejects a negative wallet grant`() {
        assertThatThrownBy {
            StarterGrant(
                defaultLoadout = emptyList(),
                walletGrants = mapOf(Currency.GOLD to -1L),
                tutorialAutoEnroll = false,
                tutorialScriptId = null,
                welcomeDialogId = null,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must be >= 0")
    }

    @Test
    fun `init rejects tutorialAutoEnroll true with blank script id`() {
        assertThatThrownBy {
            StarterGrant(
                defaultLoadout = emptyList(),
                walletGrants = emptyMap(),
                tutorialAutoEnroll = true,
                tutorialScriptId = "",
                welcomeDialogId = null,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("tutorialScriptId must be set")
    }

    @Test
    fun `init accepts a valid grant with a single item`() {
        val grant = StarterGrant(
            defaultLoadout = listOf(ItemId("example-sword")),
            walletGrants = mapOf(Currency.GOLD to 100L),
            tutorialAutoEnroll = true,
            tutorialScriptId = "getting-started",
            welcomeDialogId = null,
        )

        assertThat(grant.defaultLoadout).hasSize(1)
        assertThat(grant.walletGrants[Currency.GOLD]).isEqualTo(100L)
    }
}
