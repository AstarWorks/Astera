package com.astarworks.astera.core.application.service.survival

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.survival.GameModeContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InMemoryInventoryShareGuardTest {

    private val guard = InMemoryInventoryShareGuard()
    private val ironOre = ItemId("example-sword")
    private val coin = ItemId("example-wand")

    @Test
    fun `same mode is always allowed`() {
        assertThat(
            guard.canCarryAcrossMode(ironOre, GameModeContext.SurvivalIsland, GameModeContext.SurvivalIsland),
        ).isTrue
    }

    @Test
    fun `default policy blocks Survival items entering PvP`() {
        assertThat(
            guard.canCarryAcrossMode(ironOre, GameModeContext.SurvivalIsland, GameModeContext.PvpMatch),
        ).isFalse
    }

    @Test
    fun `default policy blocks PvE items entering PvP`() {
        assertThat(
            guard.canCarryAcrossMode(ironOre, GameModeContext.PveWorld, GameModeContext.PvpMatch),
        ).isFalse
    }

    @Test
    fun `default policy allows currency-tier items crossing into Lobby`() {
        assertThat(
            guard.canCarryAcrossMode(coin, GameModeContext.SurvivalIsland, GameModeContext.Lobby),
        ).isTrue
    }

    @Test
    fun `explicit deny overrides default-allow`() {
        guard.deny(coin, GameModeContext.Lobby, GameModeContext.PvpMatch)
        assertThat(
            guard.canCarryAcrossMode(coin, GameModeContext.Lobby, GameModeContext.PvpMatch),
        ).isFalse
    }

    @Test
    fun `clearDeny removes a registered rule`() {
        guard.deny(coin, GameModeContext.Lobby, GameModeContext.PvpMatch)
        assertThat(guard.clearDeny(coin, GameModeContext.Lobby, GameModeContext.PvpMatch)).isTrue
        assertThat(
            guard.canCarryAcrossMode(coin, GameModeContext.Lobby, GameModeContext.PvpMatch),
        ).isTrue
    }
}
