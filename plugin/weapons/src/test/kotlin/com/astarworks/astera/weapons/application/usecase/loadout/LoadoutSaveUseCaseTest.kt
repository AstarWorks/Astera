package com.astarworks.astera.weapons.application.usecase.loadout

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.Loadout
import com.astarworks.astera.core.domain.player.LoadoutSlot
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.weapons.application.service.InMemoryLoadoutRepository
import com.astarworks.astera.weapons.domain.event.LoadoutChanged
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class LoadoutSaveUseCaseTest {

    private val playerId = PlayerId(UUID.randomUUID())

    @Test
    fun `execute persists the slot assignment and publishes LoadoutChanged`() {
        val repo = InMemoryLoadoutRepository()
        val bus = FakeDomainEventBus()
        val useCase = LoadoutSaveUseCase(repo, bus)

        val result = useCase.execute(playerId, LoadoutSlot.MAIN, ItemId("example-sword"))

        assertThat(result.isSuccess).isTrue()
        assertThat(repo.find(playerId)?.itemIn(LoadoutSlot.MAIN))
            .isEqualTo(ItemId("example-sword"))
        val events = bus.publishedOfType<LoadoutChanged>()
        assertThat(events).hasSize(1)
        assertThat(events.single().playerId).isEqualTo(playerId)
        assertThat(events.single().slot).isEqualTo(LoadoutSlot.MAIN)
        assertThat(events.single().itemId).isEqualTo(ItemId("example-sword"))
    }

    @Test
    fun `execute on existing loadout updates only the named slot`() {
        val repo = InMemoryLoadoutRepository()
        val bus = FakeDomainEventBus()
        val useCase = LoadoutSaveUseCase(repo, bus)

        repo.save(
            playerId,
            Loadout(
                mapOf(
                    LoadoutSlot.MAIN to ItemId("example-sword"),
                    LoadoutSlot.SUB to ItemId("example-gun"),
                ),
            ),
        )

        useCase.execute(playerId, LoadoutSlot.MAIN, ItemId("indicator-on"))

        val found = repo.find(playerId)!!
        assertThat(found.itemIn(LoadoutSlot.MAIN)).isEqualTo(ItemId("indicator-on"))
        // SUB slot survived the partial update.
        assertThat(found.itemIn(LoadoutSlot.SUB)).isEqualTo(ItemId("example-gun"))
    }

    @Test
    fun `execute with null itemId clears the slot and publishes LoadoutChanged`() {
        val repo = InMemoryLoadoutRepository()
        val bus = FakeDomainEventBus()
        val useCase = LoadoutSaveUseCase(repo, bus)

        repo.save(playerId, Loadout(mapOf(LoadoutSlot.MAIN to ItemId("example-sword"))))

        useCase.execute(playerId, LoadoutSlot.MAIN, null)

        // After clearing the only slot, the aggregate becomes EMPTY.
        assertThat(repo.find(playerId)).isEqualTo(Loadout.EMPTY)
        val events = bus.publishedOfType<LoadoutChanged>()
        assertThat(events).hasSize(1)
        assertThat(events.single().itemId).isNull()
    }
}
