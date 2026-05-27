package com.astarworks.astera.core.application.eventbus.listeners.item

import com.astarworks.astera.core.application.port.outbound.IPlayerLocationLookup
import com.astarworks.astera.core.application.port.outbound.PlayerLocation
import com.astarworks.astera.core.domain.event.LootDropped
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.mob.LootTableId
import com.astarworks.astera.core.domain.mob.runtime.MobId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.testfixtures.item.FakeItemEntityGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class SpawnItemEntityOnLootDroppedListenerTest {

    private val world = WorldId("test-world")
    private val killer = PlayerId(UUID.randomUUID())

    private class StaticLookup(private val loc: PlayerLocation?) : IPlayerLocationLookup {
        override fun locate(playerId: PlayerId): PlayerLocation? = loc
    }

    @Test
    fun `spawns one entity per drop at the killer's last-known position`() {
        val gateway = FakeItemEntityGateway()
        val killerPos = Vec3(100.0, 64.0, 200.0)
        val lookup = StaticLookup(PlayerLocation(world, killerPos))
        val listener = SpawnItemEntityOnLootDroppedListener(gateway, lookup)

        listener.handle(
            LootDropped(
                mobId = MobId.random(),
                bossId = null,
                tableId = LootTableId("astera:test-table"),
                killerId = killer,
                drops = listOf(
                    ItemId("amethyst-sword") to 1,
                    ItemId("amethyst-sword") to 2,
                ),
                seed = 42L,
                atTick = 100L,
            ),
        )

        assertThat(gateway.spawnCalls).hasSize(2)
        assertThat(gateway.spawnCalls.map { it.position }).allMatch { it == killerPos }
        assertThat(gateway.spawnCalls.map { it.world }).allMatch { it == world }
        assertThat(gateway.spawnCalls.map { it.count }).containsExactly(1, 2)
    }

    @Test
    fun `drops are skipped when killer location is unknown`() {
        val gateway = FakeItemEntityGateway()
        val lookup = StaticLookup(null)
        val listener = SpawnItemEntityOnLootDroppedListener(gateway, lookup)

        listener.handle(
            LootDropped(
                mobId = MobId.random(),
                bossId = null,
                tableId = LootTableId("astera:test-table"),
                killerId = killer,
                drops = listOf(ItemId("amethyst-sword") to 1),
                seed = 42L,
                atTick = 100L,
            ),
        )

        assertThat(gateway.spawnCalls).isEmpty()
    }

    @Test
    fun `empty drops list is a no-op`() {
        val gateway = FakeItemEntityGateway()
        val lookup = StaticLookup(PlayerLocation(world, Vec3.ZERO))
        val listener = SpawnItemEntityOnLootDroppedListener(gateway, lookup)

        listener.handle(
            LootDropped(
                mobId = MobId.random(),
                bossId = null,
                tableId = LootTableId("astera:test-table"),
                killerId = killer,
                drops = emptyList(),
                seed = 42L,
                atTick = 100L,
            ),
        )

        assertThat(gateway.spawnCalls).isEmpty()
    }
}
