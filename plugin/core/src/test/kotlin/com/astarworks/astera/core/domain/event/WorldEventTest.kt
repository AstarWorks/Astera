package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.inventory.InventoryTransferTarget
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.BlockInteractAction
import com.astarworks.astera.core.domain.world.ChunkUnloadReason
import com.astarworks.astera.core.domain.world.TeleportReason
import com.astarworks.astera.core.domain.world.TimeChangeReason
import com.astarworks.astera.core.domain.world.WeatherState
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class WorldEventTest {

    private val player = PlayerId(UUID.randomUUID())
    private val world = WorldId("arena-1")

    @Test
    fun `PlayerMoved carries from-to and is a DomainEvent`() {
        val e = PlayerMoved(
            playerId = player,
            from = Vec3(0.0, 64.0, 0.0),
            to = Vec3(1.0, 64.0, 0.0),
            world = world,
            atTick = 42L,
        )
        assertThat(e).isInstanceOf(DomainEvent::class.java)
        assertThat(e.version).isEqualTo(1)
        assertThat(e.from.x).isEqualTo(0.0)
        assertThat(e.to.x).isEqualTo(1.0)
    }

    @Test
    fun `PlayerTeleported carries reason and cross-world handles`() {
        val e = PlayerTeleported(
            playerId = player,
            from = Vec3.ZERO,
            to = Vec3(100.0, 100.0, 100.0),
            worldFrom = WorldId("lobby"),
            worldTo = WorldId("arena-1"),
            reason = TeleportReason.MatchTransition("match-1"),
            atTick = 1L,
        )
        assertThat(e.worldFrom.value).isEqualTo("lobby")
        assertThat(e.worldTo.value).isEqualTo("arena-1")
        assertThat(e.reason).isInstanceOf(TeleportReason.MatchTransition::class.java)
    }

    @Test
    fun `PlayerInteractedBlock carries blockId and action`() {
        val e = PlayerInteractedBlock(
            playerId = player,
            world = world,
            position = Vec3(5.0, 64.0, 5.0),
            blockId = "minecraft:lever",
            action = BlockInteractAction.RightClick,
            atTick = 10L,
        )
        assertThat(e.blockId).isEqualTo("minecraft:lever")
        assertThat(e.action).isSameAs(BlockInteractAction.RightClick)
    }

    @Test
    fun `ChunkLoaded and ChunkUnloaded carry chunk coords and reason`() {
        val loaded = ChunkLoaded(world, chunkX = 4, chunkZ = -3, atTick = 1L)
        val unloaded = ChunkUnloaded(
            world,
            chunkX = 4,
            chunkZ = -3,
            reason = ChunkUnloadReason.PLAYER_LEFT_RANGE,
            atTick = 100L,
        )
        assertThat(loaded.chunkX).isEqualTo(4)
        assertThat(unloaded.reason).isEqualTo(ChunkUnloadReason.PLAYER_LEFT_RANGE)
    }

    @Test
    fun `WeatherChanged accepts vendor-neutral WeatherState`() {
        val e = WeatherChanged(
            world,
            oldWeather = WeatherState.Clear,
            newWeather = WeatherState.Rain(durationTicks = 6000),
            atTick = 1L,
        )
        assertThat(e.newWeather).isInstanceOf(WeatherState.Rain::class.java)
    }

    @Test
    fun `TimeChanged rejects out-of-range time`() {
        assertThatThrownBy {
            TimeChanged(world, oldTime = 0L, newTime = 24000L, reason = TimeChangeReason.NaturalCycle, atTick = 1L)
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            TimeChanged(world, oldTime = -1L, newTime = 0L, reason = TimeChangeReason.NaturalCycle, atTick = 1L)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `InventoryTransferred rejects empty items`() {
        assertThatThrownBy {
            InventoryTransferred(
                fromPlayer = player,
                target = InventoryTransferTarget.PlayerInventory(player),
                items = emptyMap(),
                atTick = 1L,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `InventoryTransferred rejects non-positive counts`() {
        assertThatThrownBy {
            InventoryTransferred(
                fromPlayer = null,
                target = InventoryTransferTarget.PlayerInventory(player),
                items = mapOf(ItemId("apple") to 0),
                atTick = 1L,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `InventoryTransferred accepts null fromPlayer for system origin`() {
        val e = InventoryTransferred(
            fromPlayer = null,
            target = InventoryTransferTarget.PlayerInventory(player),
            items = mapOf(ItemId("apple") to 5, ItemId("bread") to 2),
            atTick = 1L,
        )
        assertThat(e.fromPlayer).isNull()
        assertThat(e.items).hasSize(2)
    }
}
