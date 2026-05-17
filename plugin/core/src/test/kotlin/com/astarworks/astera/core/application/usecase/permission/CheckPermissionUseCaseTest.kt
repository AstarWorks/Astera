package com.astarworks.astera.core.application.usecase.permission

import com.astarworks.astera.core.application.service.InMemoryPermissionService
import com.astarworks.astera.core.domain.permission.DefaultRoles
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class CheckPermissionUseCaseTest {

    private val svc = InMemoryPermissionService()
    private val useCase = CheckPermissionUseCase(svc)
    private fun playerId() = PlayerId(UUID.randomUUID())

    @Test
    fun `returns Success(true) when player holds permission via assigned role`() {
        val player = playerId()
        svc.assign(player, DefaultRoles.MODERATOR.id)
        val perm = DefaultRoles.MODERATOR.permissions.first()

        val result = useCase.execute(player, perm)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isTrue()
    }

    @Test
    fun `returns Success(false) when player lacks permission`() {
        val player = playerId()
        svc.assign(player, DefaultRoles.PLAYER.id)

        val result = useCase.execute(player, Permission("astera.weapons.give"))

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isFalse()
    }

    @Test
    fun `fail-closed — returns Success(false) when player has no assignments`() {
        val player = playerId()

        val result = useCase.execute(player, Permission("astera.weapons.give"))

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isFalse()
    }

    @Test
    fun `returns Success(true) for ADMIN wildcard on any permission`() {
        val player = playerId()
        svc.assign(player, DefaultRoles.ADMIN.id)

        val result = useCase.execute(player, Permission("astera.totally.new.permission"))

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isTrue()
    }
}
