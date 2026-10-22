package com.astarworks.astera.core.application.eventbus.listeners.hud

import com.astarworks.astera.core.application.contents.ItemContents
import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.application.service.InMemoryResourcePoolService
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.event.PlayerQuitServer
import com.astarworks.astera.core.domain.event.ResourceReloadStarted
import com.astarworks.astera.core.domain.event.ResourceSpent
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.resource.ResourceType
import com.astarworks.astera.testfixtures.FakePlayerFoodGateway
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** F2-2 (ADR-0194): energy on the food bar, reload on the item cooldown sweep. */
class EnergyFoodBarListenerTest {

    private val alice = PlayerId(UUID.randomUUID())
    private val scheduler = FakeScheduler()
    private val bus = InMemoryDomainEventBus()
    private val pools = InMemoryResourcePoolService(scheduler, bus, ItemContents())
    private val food = FakePlayerFoodGateway()
    private val listener = EnergyFoodBarListener(pools, food, scheduler)

    private fun join() {
        pools.initialiseFor(alice)
        listener.onJoined(PlayerJoinedServer(alice))
    }

    private fun spend(amount: Int) {
        val remaining = (pools.spendResource(alice, ResourceType.Energy, amount) as com.astarworks.astera.core.domain.Result.Success).value
        listener.onSpent(ResourceSpent(alice, ResourceType.Energy, amount, remaining, scheduler.currentTick()))
    }

    @Test
    fun `joining writes a full bar`() {
        join()
        assertThat(food.getFoodLevel(alice)).isEqualTo(20)
        assertThat(food.getSaturation(alice)).isEqualTo(0.0f)
    }

    @Test
    fun `spending energy lowers the food bar and regenerating raises it`() {
        join()
        spend(50)
        assertThat(food.getFoodLevel(alice)).isEqualTo(10)
        spend(50)
        assertThat(food.getFoodLevel(alice)).isEqualTo(0)

        pools.applyRegen(alice, ResourceType.Energy, 100)
        listener.onRegenned(
            com.astarworks.astera.core.domain.event.ResourceRegenned(alice, ResourceType.Energy, 100, 100, scheduler.currentTick()),
        )
        assertThat(food.getFoodLevel(alice)).isEqualTo(20)
    }

    @Test
    fun `ammo spending does not touch the food bar`() {
        join()
        val before = food.calls.size
        pools.spendResource(alice, ResourceType.Ammo, 3)
        listener.onSpent(ResourceSpent(alice, ResourceType.Ammo, 3, 27, scheduler.currentTick()))
        assertThat(food.calls.size).isEqualTo(before)
    }

    @Test
    fun `a vanilla respawn that reset the food level is corrected by the poll`() {
        join()
        spend(60)
        val level = food.getFoodLevel(alice)
        assertThat(level).isEqualTo(8)

        food.setFoodLevel(alice, 20) // what vanilla does on respawn
        scheduler.advanceTicks(EnergyFoodBarListener.RESYNC_PERIOD_TICKS)

        assertThat(food.getFoodLevel(alice)).isEqualTo(8)
    }

    @Test
    fun `players who left are no longer written`() {
        join()
        listener.onQuit(PlayerQuitServer(alice))
        val before = food.calls.size
        scheduler.advanceTicks(EnergyFoodBarListener.RESYNC_PERIOD_TICKS)
        assertThat(food.calls.size).isEqualTo(before)
    }
}
