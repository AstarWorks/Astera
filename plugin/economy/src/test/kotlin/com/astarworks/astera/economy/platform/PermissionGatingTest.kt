package com.astarworks.astera.economy.platform

import com.astarworks.astera.core.application.command.CommandContext
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.requirePermission
import com.astarworks.astera.core.application.service.InMemoryPermissionService
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.DefaultRoles
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeScheduler
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * R1.c — verifies that the three admin-grade `/astera-economy currency …`
 * commands are gated by [requirePermission] with the correct permission IDs
 * and that those IDs are covered by [DefaultRoles.MODERATOR] but not by
 * [DefaultRoles.PLAYER]. Console invocations bypass.
 *
 * The test does not boot the full plugin; it asserts the gating policy by
 * decorating a no-op literal with the same permission node that
 * `AsteraEconomyPlugin.buildCommandSpec(...)` wires in.
 */
class PermissionGatingTest {

    private val scheduler = FakeScheduler()

    private fun ctx(sender: PlayerId?): CommandContext =
        CommandContext(sender = sender, args = emptyMap(), scheduler = scheduler)

    private fun gatedNoop(perm: Permission, svc: InMemoryPermissionService): CommandSpec.Literal =
        CommandSpec.Literal(
            name = "noop",
            subcommands = emptyList(),
            executor = { CommandResult.Success },
        ).requirePermission(permissionService = svc, permission = perm)

    private fun assertGatedByModerator(permissionId: String) = runBlocking {
        val svc = InMemoryPermissionService()
        val moderator = PlayerId(UUID.randomUUID())
        val player = PlayerId(UUID.randomUUID())
        svc.assign(moderator, DefaultRoles.MODERATOR.id)
        svc.assign(player, DefaultRoles.PLAYER.id)
        val spec = gatedNoop(Permission(permissionId), svc)

        // PLAYER → deny
        val playerResult = spec.executor!!(ctx(player))
        assertThat(playerResult).isInstanceOf(CommandResult.Failure::class.java)
        val failure = playerResult as CommandResult.Failure
        assertThat(failure.messageKey).isEqualTo(MessageKey("astera.permission.denied"))
        assertThat(failure.placeholders["permission"]).isEqualTo(permissionId)

        // MODERATOR → pass
        val modResult = spec.executor!!(ctx(moderator))
        assertThat(modResult).isEqualTo(CommandResult.Success)

        // Console (null sender) → pass via consoleBypass=true
        val consoleResult = spec.executor!!(ctx(sender = null))
        assertThat(consoleResult).isEqualTo(CommandResult.Success)
    }

    @Test
    fun `astera-economy currency deposit is gated to MODERATOR plus console`() {
        assertGatedByModerator("astera.economy.currency.deposit")
    }

    @Test
    fun `astera-economy currency withdraw is gated to MODERATOR plus console`() {
        assertGatedByModerator("astera.economy.currency.withdraw")
    }

    @Test
    fun `astera-economy currency transfer is gated to MODERATOR plus console`() {
        assertGatedByModerator("astera.economy.currency.transfer")
    }

    @Test
    fun `DefaultRoles MODERATOR carries the three currency admin permissions`() {
        val perms = DefaultRoles.MODERATOR.permissions.map { it.id }.toSet()
        assertThat(perms).contains(
            "astera.economy.currency.deposit",
            "astera.economy.currency.withdraw",
            "astera.economy.currency.transfer",
        )
    }

    @Test
    fun `DefaultRoles PLAYER does not carry currency admin permissions`() {
        val perms = DefaultRoles.PLAYER.permissions.map { it.id }.toSet()
        assertThat(perms).doesNotContain(
            "astera.economy.currency.deposit",
            "astera.economy.currency.withdraw",
            "astera.economy.currency.transfer",
        )
    }
}
