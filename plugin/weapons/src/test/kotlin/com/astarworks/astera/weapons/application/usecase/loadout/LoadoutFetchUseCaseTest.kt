package com.astarworks.astera.weapons.application.usecase.loadout

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.Loadout
import com.astarworks.astera.core.domain.player.LoadoutSlot
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.weapons.application.service.InMemoryLoadoutRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class LoadoutFetchUseCaseTest {

    private val playerId = PlayerId(UUID.randomUUID())

    @Test
    fun `execute returns EMPTY when nothing has been saved`() {
        val repo = InMemoryLoadoutRepository()
        val useCase = LoadoutFetchUseCase(repo)
        assertThat(useCase.execute(playerId)).isEqualTo(Loadout.EMPTY)
    }

    @Test
    fun `execute returns the persisted Loadout when one exists`() {
        val repo = InMemoryLoadoutRepository()
        val loadout = Loadout(mapOf(LoadoutSlot.MAIN to ItemId("example-sword")))
        repo.save(playerId, loadout)
        val useCase = LoadoutFetchUseCase(repo)
        assertThat(useCase.execute(playerId)).isEqualTo(loadout)
    }
}
