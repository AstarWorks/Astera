package com.astarworks.astera.match.platform

import com.astarworks.astera.core.application.command.CommandContext
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.requirePermission
import com.astarworks.astera.core.application.service.InMemoryPermissionService
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.DefaultRoles
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.consoleActor
import com.astarworks.astera.testfixtures.invokeAs
import com.astarworks.astera.testfixtures.playerActor
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * R1.c — verifies that the three admin `/astera match …` lifecycle commands
 * are gated by [requirePermission] with the correct permission IDs and that
 * those IDs are covered by [DefaultRoles.MODERATOR] but not by
 * [DefaultRoles.PLAYER]. Console invocations bypass.
 */
class PermissionGatingTest {

    private fun gatedNoop(perm: Permission, svc: InMemoryPermissionService): CommandSpec.Literal =
        CommandSpec.Literal(
            name = "noop",
            subcommands = emptyList(),
            executor = { CommandResult.Success },
        ).requirePermission(perm)

    private fun assertGatedByModerator(permissionId: String) = runBlocking {
        val svc = InMemoryPermissionService()
        val moderator = PlayerId(UUID.randomUUID())
        val player = PlayerId(UUID.randomUUID())
        svc.assign(moderator, DefaultRoles.MODERATOR.id)
        svc.assign(player, DefaultRoles.PLAYER.id)
        val spec = gatedNoop(Permission(permissionId), svc)

        // PLAYER → deny
        val playerResult = spec.invokeAs(svc, playerActor(player)).result
        assertThat(playerResult).isInstanceOf(CommandResult.Failure::class.java)
        val failure = playerResult as CommandResult.Failure
        assertThat(failure.messageKey).isEqualTo(MessageKey("astera.permission.denied"))
        assertThat(failure.placeholders["permission"]).isEqualTo(permissionId)

        // MODERATOR → pass
        val modResult = spec.invokeAs(svc, playerActor(moderator)).result
        assertThat(modResult).isEqualTo(CommandResult.Success)

        // Console (null sender) → pass via consoleBypass=true
        val consoleResult = spec.invokeAs(svc, consoleActor()).result
        assertThat(consoleResult).isEqualTo(CommandResult.Success)
    }

    @Test
    fun `astera match start is gated to MODERATOR plus console`() {
        assertGatedByModerator("astera.match.admin.start")
    }

    @Test
    fun `astera match end is gated to MODERATOR plus console`() {
        assertGatedByModerator("astera.match.admin.end")
    }

    @Test
    fun `astera match phase is gated to MODERATOR plus console`() {
        assertGatedByModerator("astera.match.admin.phase")
    }

    @Test
    fun `DefaultRoles MODERATOR carries the three match admin permissions`() {
        val perms = DefaultRoles.MODERATOR.permissions.map { it.id }.toSet()
        assertThat(perms).contains(
            "astera.match.admin.start",
            "astera.match.admin.end",
            "astera.match.admin.phase",
        )
    }

    @Test
    fun `DefaultRoles PLAYER does not carry match admin permissions`() {
        val perms = DefaultRoles.PLAYER.permissions.map { it.id }.toSet()
        assertThat(perms).doesNotContain(
            "astera.match.admin.start",
            "astera.match.admin.end",
            "astera.match.admin.phase",
        )
    }
}
