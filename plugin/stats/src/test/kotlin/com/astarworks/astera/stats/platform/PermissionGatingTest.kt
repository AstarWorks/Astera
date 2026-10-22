package com.astarworks.astera.stats.platform

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
 * R1.c — verifies that:
 *
 * - `/astera stats xp grant` is gated by `astera.stats.xp.grant` via
 *   [requirePermission]; PLAYER is denied, MODERATOR passes, console bypasses.
 * - `/astera stats show <other>` requires `astera.stats.show.other` when the
 *   sender is inspecting a player other than themselves. Self-inspection is
 *   open to everyone (no permission required). This branch is implemented
 *   inline in `AsteraStatsPlugin.buildCommandSpec(...)`; the test exercises
 *   the same `IPermissionService.check(...)` call directly.
 */
class PermissionGatingTest {

    private fun gatedNoop(perm: Permission, svc: InMemoryPermissionService): CommandSpec.Literal =
        CommandSpec.Literal(
            name = "noop",
            subcommands = emptyList(),
            executor = { CommandResult.Success },
        ).requirePermission(perm)

    // -------------------------------------------------------------------------
    // xp grant — gated to MODERATOR
    // -------------------------------------------------------------------------

    @Test
    fun `xp grant - PLAYER role is denied`() = runBlocking {
        val svc = InMemoryPermissionService()
        val player = PlayerId(UUID.randomUUID())
        svc.assign(player, DefaultRoles.PLAYER.id)
        val spec = gatedNoop(Permission("astera.stats.xp.grant"), svc)

        val result = spec.invokeAs(svc, playerActor(player)).result

        assertThat(result).isInstanceOf(CommandResult.Failure::class.java)
        val failure = result as CommandResult.Failure
        assertThat(failure.messageKey).isEqualTo(MessageKey("astera.permission.denied"))
        assertThat(failure.placeholders["permission"]).isEqualTo("astera.stats.xp.grant")
    }

    @Test
    fun `xp grant - MODERATOR role passes`() = runBlocking {
        val svc = InMemoryPermissionService()
        val mod = PlayerId(UUID.randomUUID())
        svc.assign(mod, DefaultRoles.MODERATOR.id)
        val spec = gatedNoop(Permission("astera.stats.xp.grant"), svc)

        val result = spec.invokeAs(svc, playerActor(mod)).result

        assertThat(result).isEqualTo(CommandResult.Success)
    }

    @Test
    fun `xp grant - console bypasses permission check`() = runBlocking {
        val svc = InMemoryPermissionService()
        val spec = gatedNoop(Permission("astera.stats.xp.grant"), svc)

        val result = spec.invokeAs(svc, consoleActor()).result

        assertThat(result).isEqualTo(CommandResult.Success)
    }

    // -------------------------------------------------------------------------
    // show <other> — inline branch in AsteraStatsPlugin: self always OK,
    // other requires astera.stats.show.other.
    // -------------------------------------------------------------------------

    /**
     * Mirrors the inline branch in `AsteraStatsPlugin.buildCommandSpec(...)`:
     * - null sender (console) → bypass
     * - sender == target → no permission required
     * - sender != target → must hold astera.stats.show.other
     */
    private fun showStatsAllowed(sender: PlayerId?, target: PlayerId, svc: InMemoryPermissionService): Boolean {
        if (sender == null) return true
        if (sender == target) return true
        return svc.check(sender, Permission("astera.stats.show.other"))
    }

    @Test
    fun `show self is allowed for PLAYER without permission`() {
        val svc = InMemoryPermissionService()
        val player = PlayerId(UUID.randomUUID())
        svc.assign(player, DefaultRoles.PLAYER.id)

        assertThat(showStatsAllowed(sender = player, target = player, svc = svc)).isTrue()
    }

    @Test
    fun `show other is denied for PLAYER role`() {
        val svc = InMemoryPermissionService()
        val player = PlayerId(UUID.randomUUID())
        val other = PlayerId(UUID.randomUUID())
        svc.assign(player, DefaultRoles.PLAYER.id)

        assertThat(showStatsAllowed(sender = player, target = other, svc = svc)).isFalse()
    }

    @Test
    fun `show other is allowed for MODERATOR role`() {
        val svc = InMemoryPermissionService()
        val mod = PlayerId(UUID.randomUUID())
        val other = PlayerId(UUID.randomUUID())
        svc.assign(mod, DefaultRoles.MODERATOR.id)

        assertThat(showStatsAllowed(sender = mod, target = other, svc = svc)).isTrue()
    }

    @Test
    fun `show other from console bypasses permission`() {
        val svc = InMemoryPermissionService()
        val other = PlayerId(UUID.randomUUID())

        assertThat(showStatsAllowed(sender = null, target = other, svc = svc)).isTrue()
    }

    // -------------------------------------------------------------------------
    // DefaultRoles sanity
    // -------------------------------------------------------------------------

    @Test
    fun `DefaultRoles MODERATOR carries the stats admin permissions`() {
        val perms = DefaultRoles.MODERATOR.permissions.map { it.id }.toSet()
        assertThat(perms).contains(
            "astera.stats.xp.grant",
            "astera.stats.show.other",
        )
    }

    @Test
    fun `DefaultRoles PLAYER does not carry stats admin permissions`() {
        val perms = DefaultRoles.PLAYER.permissions.map { it.id }.toSet()
        assertThat(perms).doesNotContain(
            "astera.stats.xp.grant",
            "astera.stats.show.other",
        )
    }
}
