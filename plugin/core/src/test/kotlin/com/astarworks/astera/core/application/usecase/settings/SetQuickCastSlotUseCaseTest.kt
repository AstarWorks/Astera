package com.astarworks.astera.core.application.usecase.settings

import com.astarworks.astera.core.application.service.InMemoryPlayerSettingsRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class SetQuickCastSlotUseCaseTest {

    private fun playerId(): PlayerId = PlayerId(UUID.randomUUID())

    @Test
    fun `set binds a skill to slot 1`() {
        val repo = InMemoryPlayerSettingsRepository()
        val id = playerId()

        val result = SetQuickCastSlotUseCase(repo).set(id, slot = 1, skillId = "astera:fireball")

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(repo.find(id)!!.quickCastSlots).containsEntry(1, "astera:fireball")
    }

    @Test
    fun `set with skillId null clears the slot`() {
        val repo = InMemoryPlayerSettingsRepository()
        val id = playerId()
        val uc = SetQuickCastSlotUseCase(repo)
        uc.set(id, slot = 3, skillId = "astera:dash")
        assertThat(repo.find(id)!!.quickCastSlots).containsKey(3)

        uc.set(id, slot = 3, skillId = null)

        assertThat(repo.find(id)!!.quickCastSlots).doesNotContainKey(3)
    }

    @Test
    fun `set rejects slot 0`() {
        val repo = InMemoryPlayerSettingsRepository()
        val id = playerId()

        val result = SetQuickCastSlotUseCase(repo).set(id, slot = 0, skillId = "x")

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).error).isEqualTo(SettingsError.InvalidSlot(0))
        assertThat(repo.find(id)).isNull()
    }

    @Test
    fun `set rejects slot 10`() {
        val repo = InMemoryPlayerSettingsRepository()
        val id = playerId()

        val result = SetQuickCastSlotUseCase(repo).set(id, slot = 10, skillId = "x")

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).error).isEqualTo(SettingsError.InvalidSlot(10))
    }

    @Test
    fun `set preserves bindings on other slots`() {
        val repo = InMemoryPlayerSettingsRepository()
        val id = playerId()
        val uc = SetQuickCastSlotUseCase(repo)
        uc.set(id, slot = 1, skillId = "astera:fireball")
        uc.set(id, slot = 2, skillId = "astera:dash")

        uc.set(id, slot = 9, skillId = "astera:heal")

        val bindings = repo.find(id)!!.quickCastSlots
        assertThat(bindings).containsEntry(1, "astera:fireball")
        assertThat(bindings).containsEntry(2, "astera:dash")
        assertThat(bindings).containsEntry(9, "astera:heal")
    }
}
