package com.astarworks.astera.match.application.gamemode

import com.astarworks.astera.match.application.service.InMemoryGameModeFrameworkRegistry
import com.astarworks.astera.match.domain.mode.GameModeId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0106 Wave 5gamemode — register/get/all happy path + last-write-wins
 * tests for the in-memory framework registry.
 */
class InMemoryGameModeFrameworkRegistryTest {

    @Test
    fun `register then get round-trips by id`() {
        val registry = InMemoryGameModeFrameworkRegistry()
        val norma = NormaGameMode()

        registry.register(norma)

        assertThat(registry.get(GameModeId("norma"))).isSameAs(norma)
        assertThat(registry.all()).containsExactly(norma)
    }

    @Test
    fun `get returns null for an unknown id`() {
        val registry = InMemoryGameModeFrameworkRegistry()
        assertThat(registry.get(GameModeId("unknown-mode"))).isNull()
    }

    @Test
    fun `register is last-write-wins for the same id`() {
        val registry = InMemoryGameModeFrameworkRegistry()
        val first = NormaGameMode()
        val second = NormaGameMode()

        registry.register(first)
        registry.register(second)

        assertThat(registry.get(GameModeId("norma"))).isSameAs(second)
        assertThat(registry.all()).containsExactly(second)
    }
}
