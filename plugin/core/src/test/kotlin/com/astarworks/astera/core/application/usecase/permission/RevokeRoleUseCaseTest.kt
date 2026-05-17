package com.astarworks.astera.core.application.usecase.permission

import com.astarworks.astera.core.application.port.outbound.RevokeRoleError
import com.astarworks.astera.core.application.service.InMemoryPermissionService
import com.astarworks.astera.core.domain.permission.DefaultRoles
import com.astarworks.astera.core.domain.permission.RoleId
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class RevokeRoleUseCaseTest {

    private val svc = InMemoryPermissionService()
    private val useCase = RevokeRoleUseCase(svc)
    private fun playerId() = PlayerId(UUID.randomUUID())

    @Test
    fun `happy path — revokes previously assigned role`() {
        val player = playerId()
        svc.assign(player, DefaultRoles.PLAYER.id)

        val result = useCase.execute(player, DefaultRoles.PLAYER.id)

        assertThat(result.isSuccess).isTrue()
        assertThat(svc.rolesOf(player)).doesNotContain(DefaultRoles.PLAYER.id)
    }

    @Test
    fun `NotAssigned when role was never assigned`() {
        val player = playerId()

        val result = useCase.execute(player, DefaultRoles.MODERATOR.id)

        assertThat(result.isFailure).isTrue()
        val error = result.errorOrNull()
        assertThat(error).isInstanceOf(RevokeRoleError.NotAssigned::class.java)
        val notAssigned = error as RevokeRoleError.NotAssigned
        assertThat(notAssigned.playerId).isEqualTo(player)
        assertThat(notAssigned.roleId).isEqualTo(DefaultRoles.MODERATOR.id)
    }

    @Test
    fun `NotAssigned when revoking an already revoked role`() {
        val player = playerId()
        svc.assign(player, DefaultRoles.PLAYER.id)
        useCase.execute(player, DefaultRoles.PLAYER.id)

        val result = useCase.execute(player, DefaultRoles.PLAYER.id)

        assertThat(result.isFailure).isTrue()
        assertThat(result.errorOrNull()).isInstanceOf(RevokeRoleError.NotAssigned::class.java)
    }
}
