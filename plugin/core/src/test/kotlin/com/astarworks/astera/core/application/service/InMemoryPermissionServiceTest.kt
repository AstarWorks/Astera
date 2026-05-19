package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.AssignRoleError
import com.astarworks.astera.core.application.port.outbound.RevokeRoleError
import com.astarworks.astera.core.domain.permission.DefaultRoles
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.permission.RoleId
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryPermissionServiceTest {

    private fun playerId() = PlayerId(UUID.randomUUID())

    @Test
    fun `default roles are seeded on construction`() {
        val svc = InMemoryPermissionService()
        val ids = svc.allDefinitions().map { it.id }
        assertThat(ids).containsExactlyInAnyOrder(
            DefaultRoles.ADMIN.id,
            DefaultRoles.MODERATOR.id,
            // Wave 5h (ADR-0074 §4) — junior-mod role for reversible mod tools only.
            DefaultRoles.MODERATOR_LITE.id,
            DefaultRoles.PLAYER.id,
        )
    }

    @Test
    fun `assign new role then check returns true for that role's permissions`() {
        val svc = InMemoryPermissionService()
        val player = playerId()

        svc.assign(player, DefaultRoles.MODERATOR.id)

        val perm = DefaultRoles.MODERATOR.permissions.first()
        assertThat(svc.check(player, perm)).isTrue()
    }

    @Test
    fun `assign same role twice returns Failure AlreadyAssigned`() {
        val svc = InMemoryPermissionService()
        val player = playerId()

        svc.assign(player, DefaultRoles.PLAYER.id)
        val second = svc.assign(player, DefaultRoles.PLAYER.id)

        assertThat(second.isFailure).isTrue()
        assertThat(second.errorOrNull()).isInstanceOf(AssignRoleError.AlreadyAssigned::class.java)
    }

    @Test
    fun `revoke unassigned role returns Failure NotAssigned`() {
        val svc = InMemoryPermissionService()
        val player = playerId()

        val result = svc.revoke(player, DefaultRoles.PLAYER.id)

        assertThat(result.isFailure).isTrue()
        assertThat(result.errorOrNull()).isInstanceOf(RevokeRoleError.NotAssigned::class.java)
    }

    @Test
    fun `ADMIN wildcard matches any permission`() {
        val svc = InMemoryPermissionService()
        val player = playerId()

        svc.assign(player, DefaultRoles.ADMIN.id)

        assertThat(svc.check(player, Permission("astera.weapons.give"))).isTrue()
        assertThat(svc.check(player, Permission("astera.some.completely.new.node"))).isTrue()
    }

    @Test
    fun `PLAYER without a permission returns false`() {
        val svc = InMemoryPermissionService()
        val player = playerId()

        svc.assign(player, DefaultRoles.PLAYER.id)

        assertThat(svc.check(player, Permission("astera.weapons.give"))).isFalse()
    }

    @Test
    fun `assign unknown role returns Failure RoleNotFound`() {
        val svc = InMemoryPermissionService()
        val player = playerId()

        val result = svc.assign(player, RoleId("nonexistent"))

        assertThat(result.isFailure).isTrue()
        assertThat(result.errorOrNull()).isInstanceOf(AssignRoleError.RoleNotFound::class.java)
    }

    @Test
    fun `rolesOf returns assigned roles`() {
        val svc = InMemoryPermissionService()
        val player = playerId()

        svc.assign(player, DefaultRoles.PLAYER.id)
        svc.assign(player, DefaultRoles.MODERATOR.id)

        assertThat(svc.rolesOf(player)).containsExactlyInAnyOrder(
            DefaultRoles.PLAYER.id,
            DefaultRoles.MODERATOR.id,
        )
    }

    @Test
    fun `revoke assigned role removes it`() {
        val svc = InMemoryPermissionService()
        val player = playerId()

        svc.assign(player, DefaultRoles.PLAYER.id)
        val result = svc.revoke(player, DefaultRoles.PLAYER.id)

        assertThat(result.isSuccess).isTrue()
        assertThat(svc.rolesOf(player)).isEmpty()
    }
}
