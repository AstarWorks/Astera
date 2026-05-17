package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.ILoadoutRepository
import com.astarworks.astera.core.domain.gui.BindingKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.Loadout
import com.astarworks.astera.core.domain.player.LoadoutSlot
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Unit tests for [DefaultGuiBindingResolver] (Wave 4.5, ADR-0027).
 *
 * Uses an inline [FakeLoadoutRepo] so we don't reach into `plugin/test-fixtures`
 * (worker territory boundary — see Wave 4.5 worker assignment).
 */
class DefaultGuiBindingResolverTest {

    /** Inline fake of [ILoadoutRepository]; backed by a mutable map. */
    private class FakeLoadoutRepo : ILoadoutRepository {
        private val store: MutableMap<PlayerId, Loadout> = mutableMapOf()

        override fun find(playerId: PlayerId): Loadout? = store[playerId]

        override fun save(playerId: PlayerId, loadout: Loadout) {
            store[playerId] = loadout
        }

        override fun all(): Collection<Pair<PlayerId, Loadout>> =
            store.entries.map { it.key to it.value }
    }

    private fun playerId(): PlayerId = PlayerId(UUID.randomUUID())

    @Test
    fun `resolve player_loadout_main returns the configured ItemId for that slot`() {
        val repo = FakeLoadoutRepo()
        val player = playerId()
        repo.save(player, Loadout(mapOf(LoadoutSlot.MAIN to ItemId("example-sword"))))

        val resolver = DefaultGuiBindingResolver(repo)

        val result = resolver.resolve(BindingKey("player.loadout.main"), player)

        assertThat(result).isEqualTo(ItemId("example-sword"))
    }

    @Test
    fun `resolve player_loadout_sub returns null when slot is empty`() {
        val repo = FakeLoadoutRepo()
        val player = playerId()
        repo.save(player, Loadout(mapOf(LoadoutSlot.MAIN to ItemId("example-sword"))))

        val resolver = DefaultGuiBindingResolver(repo)

        val result = resolver.resolve(BindingKey("player.loadout.sub"), player)

        assertThat(result).isNull()
    }

    @Test
    fun `resolve when viewer is null returns null`() {
        val repo = FakeLoadoutRepo()
        repo.save(playerId(), Loadout(mapOf(LoadoutSlot.MAIN to ItemId("example-sword"))))

        val resolver = DefaultGuiBindingResolver(repo)

        val result = resolver.resolve(BindingKey("player.loadout.main"), viewer = null)

        assertThat(result).isNull()
    }

    @Test
    fun `resolve when loadout repo has no entry returns null`() {
        val repo = FakeLoadoutRepo() // empty
        val resolver = DefaultGuiBindingResolver(repo)

        val result = resolver.resolve(BindingKey("player.loadout.main"), playerId())

        assertThat(result).isNull()
    }

    @Test
    fun `resolve unknown slot token returns null`() {
        val repo = FakeLoadoutRepo()
        val player = playerId()
        repo.save(player, Loadout(mapOf(LoadoutSlot.MAIN to ItemId("example-sword"))))

        val resolver = DefaultGuiBindingResolver(repo)

        val result = resolver.resolve(BindingKey("player.loadout.invalid"), player)

        assertThat(result).isNull()
    }

    @Test
    fun `resolve unsupported binding prefix returns null`() {
        val repo = FakeLoadoutRepo()
        val player = playerId()
        repo.save(player, Loadout(mapOf(LoadoutSlot.MAIN to ItemId("example-sword"))))

        val resolver = DefaultGuiBindingResolver(repo)

        assertThat(resolver.resolve(BindingKey("player.coins.star"), player)).isNull()
        assertThat(resolver.resolve(BindingKey("player.kills"), player)).isNull()
    }

    @Test
    fun `every LoadoutSlot variant is resolvable via its lowercase token`() {
        val repo = FakeLoadoutRepo()
        val player = playerId()

        // Pin every slot to a distinct ItemId so the lookup proves end-to-end.
        val pins: Map<LoadoutSlot, ItemId> = LoadoutSlot.entries
            .associateWith { ItemId("pin-${it.name.lowercase()}") }
        repo.save(player, Loadout(pins))

        val resolver = DefaultGuiBindingResolver(repo)

        LoadoutSlot.entries.forEach { slot ->
            val key = BindingKey("player.loadout.${slot.name.lowercase()}")
            val resolved = resolver.resolve(key, player)
            assertThat(resolved)
                .describedAs("slot %s should resolve via token %s", slot, slot.name.lowercase())
                .isEqualTo(ItemId("pin-${slot.name.lowercase()}"))
        }
    }

    @Test
    fun `lazy provider variant defers loadout repo lookup until resolve is called`() {
        val repo = FakeLoadoutRepo()
        val player = playerId()
        repo.save(player, Loadout(mapOf(LoadoutSlot.ULTIMATE to ItemId("nova-blade"))))

        var providerCalls = 0
        val resolver = DefaultGuiBindingResolver(
            loadoutsProvider = {
                providerCalls += 1
                repo
            },
        )

        // Construction must not have invoked the provider yet.
        assertThat(providerCalls).isZero()

        val result = resolver.resolve(BindingKey("player.loadout.ultimate"), player)

        assertThat(result).isEqualTo(ItemId("nova-blade"))
        assertThat(providerCalls).isEqualTo(1)
    }

    @Test
    fun `lazy provider is not called when viewer is null`() {
        var providerCalls = 0
        val resolver = DefaultGuiBindingResolver(
            loadoutsProvider = {
                providerCalls += 1
                FakeLoadoutRepo()
            },
        )

        val result = resolver.resolve(BindingKey("player.loadout.main"), viewer = null)

        assertThat(result).isNull()
        assertThat(providerCalls).isZero()
    }

    @Test
    fun `lazy provider is not called for unknown slot tokens`() {
        var providerCalls = 0
        val resolver = DefaultGuiBindingResolver(
            loadoutsProvider = {
                providerCalls += 1
                FakeLoadoutRepo()
            },
        )

        val result = resolver.resolve(BindingKey("player.loadout.invalid"), playerId())

        assertThat(result).isNull()
        assertThat(providerCalls).isZero()
    }
}
