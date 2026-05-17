package com.astarworks.astera.core.application.usecase.permission

import com.astarworks.astera.core.application.port.outbound.AssignRoleError
import com.astarworks.astera.core.application.service.InMemoryPermissionService
import com.astarworks.astera.core.domain.permission.DefaultRoles
import com.astarworks.astera.core.domain.permission.RoleId
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class AssignRoleUseCaseTest {

    private val svc = InMemoryPermissionService()
    private val useCase = AssignRoleUseCase(svc)
    private fun playerId() = PlayerId(UUID.randomUUID())

    @Test
    fun `happy path — assigns known role successfully`() {
        val player = playerId()

        val result = useCase.execute(player, DefaultRoles.PLAYER.id)

        assertThat(result.isSuccess).isTrue()
        assertThat(svc.rolesOf(player)).contains(DefaultRoles.PLAYER.id)
    }

    @Test
    fun `RoleNotFound when roleId does not exist`() {
        val player = playerId()

        val result = useCase.execute(player, RoleId("unknown-role"))

        assertThat(result.isFailure).isTrue()
        val error = result.errorOrNull()
        assertThat(error).isInstanceOf(AssignRoleError.RoleNotFound::class.java)
        assertThat((error as AssignRoleError.RoleNotFound).roleId).isEqualTo(RoleId("unknown-role"))
    }

    @Test
    fun `AlreadyAssigned when role assigned twice`() {
        val player = playerId()
        useCase.execute(player, DefaultRoles.MODERATOR.id)

        val result = useCase.execute(player, DefaultRoles.MODERATOR.id)

        assertThat(result.isFailure).isTrue()
        val error = result.errorOrNull()
        assertThat(error).isInstanceOf(AssignRoleError.AlreadyAssigned::class.java)
        val alreadyAssigned = error as AssignRoleError.AlreadyAssigned
        assertThat(alreadyAssigned.playerId).isEqualTo(player)
        assertThat(alreadyAssigned.roleId).isEqualTo(DefaultRoles.MODERATOR.id)
    }
}
