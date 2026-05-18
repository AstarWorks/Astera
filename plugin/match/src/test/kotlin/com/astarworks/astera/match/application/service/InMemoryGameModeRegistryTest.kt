package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.match.domain.mode.GameModeId
import com.astarworks.astera.match.domain.mode.StealJewelry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class InMemoryGameModeRegistryTest {

    private lateinit var registry: InMemoryGameModeRegistry

    private val mode = StealJewelry(
        id = GameModeId("steal-jewelry"),
        displayNameKey = MessageKey("astera.mode.steal-jewelry.name"),
        descriptionKey = MessageKey("astera.mode.steal-jewelry.desc"),
        depositRegionIds = mapOf(TeamId("red") to "gem_deposit_red"),
    )

    private val otherMode = StealJewelry(
        id = GameModeId("steal-jewelry-mini"),
        displayNameKey = MessageKey("astera.mode.steal-jewelry-mini.name"),
        descriptionKey = MessageKey("astera.mode.steal-jewelry-mini.desc"),
        gemsPerTeam = 1,
        depositRegionIds = mapOf(TeamId("blue") to "gem_deposit_blue"),
    )

    @BeforeEach
    fun setUp() {
        registry = InMemoryGameModeRegistry()
    }

    @Test
    fun `get returns null before any registration`() {
        assertThat(registry.get(mode.id)).isNull()
    }

    @Test
    fun `register then get returns the same instance`() {
        registry.register(mode)
        assertThat(registry.get(mode.id)).isSameAs(mode)
    }

    @Test
    fun `register overwrites prior entry with same id`() {
        registry.register(mode)
        val replacement = mode.copy(gemsPerTeam = 5)
        registry.register(replacement)

        assertThat(registry.get(mode.id)).isSameAs(replacement)
        assertThat(registry.all()).hasSize(1)
    }

    @Test
    fun `all returns every registered mode`() {
        registry.register(mode)
        registry.register(otherMode)

        assertThat(registry.all()).containsExactlyInAnyOrder(mode, otherMode)
    }

    @Test
    fun `all returns empty when nothing registered`() {
        assertThat(registry.all()).isEmpty()
    }
}
