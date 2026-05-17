package com.astarworks.astera.core.application.command

import com.astarworks.astera.core.application.port.outbound.AssignRoleError
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.RevokeRoleError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.permission.RoleDefinition
import com.astarworks.astera.core.domain.permission.RoleId
import com.astarworks.astera.core.domain.player.PlayerId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.time.Duration

class CommandPermissionTest {

    // -------------------------------------------------------------------------
    // Fakes
    // -------------------------------------------------------------------------

    private fun fakePermissionService(grants: Boolean): IPermissionService =
        object : IPermissionService {
            override fun assign(playerId: PlayerId, roleId: RoleId): Result<Unit, AssignRoleError> =
                Result.success(Unit)
            override fun revoke(playerId: PlayerId, roleId: RoleId): Result<Unit, RevokeRoleError> =
                Result.success(Unit)
            override fun check(playerId: PlayerId, permission: Permission): Boolean = grants
            override fun rolesOf(playerId: PlayerId): Set<RoleId> = emptySet()
            override fun definitionOf(roleId: RoleId): RoleDefinition? = null
            override fun allDefinitions(): Collection<RoleDefinition> = emptyList()
        }

    private val noOpHandle: IScheduler.TaskHandle = object : IScheduler.TaskHandle {
        override fun cancel() = Unit
        override val isActive: Boolean get() = false
    }

    private val noOpScheduler: IScheduler = object : IScheduler {
        override fun currentTick(): Long = 0L
        override fun runLater(delay: Duration, block: () -> Unit): IScheduler.TaskHandle = noOpHandle
        override fun runRepeating(initialDelay: Duration, period: Duration, block: () -> Unit): IScheduler.TaskHandle = noOpHandle
        override fun runAsync(block: () -> Unit): IScheduler.TaskHandle = noOpHandle
        override fun scheduleAt(tickAbsolute: Long, block: () -> Unit): IScheduler.TaskHandle = noOpHandle
        override suspend fun awaitTicks(ticks: Long) = Unit
        override val mainDispatcher: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val asyncDispatcher: CoroutineDispatcher get() = Dispatchers.Unconfined
    }

    private val testPermission = Permission("astera.weapons.give")
    private val testPlayerId = PlayerId(UUID.randomUUID())

    private fun ctx(sender: PlayerId?): CommandContext =
        CommandContext(sender = sender, args = emptyMap(), scheduler = noOpScheduler)

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    fun `requirePermission - sender has permission - executor invoked - success`() = runBlocking {
        var executed = false
        val spec = CommandSpec.Literal(
            name = "give",
            subcommands = emptyList(),
            executor = { CommandResult.Success.also { executed = true } },
        ).requirePermission(fakePermissionService(grants = true), testPermission)

        val result = spec.executor!!(ctx(testPlayerId))

        assertThat(executed).isTrue()
        assertThat(result).isEqualTo(CommandResult.Success)
    }

    @Test
    fun `requirePermission - sender lacks permission - executor not invoked - failure with permission key`() = runBlocking {
        var executed = false
        val spec = CommandSpec.Literal(
            name = "give",
            subcommands = emptyList(),
            executor = { CommandResult.Success.also { executed = true } },
        ).requirePermission(fakePermissionService(grants = false), testPermission)

        val result = spec.executor!!(ctx(testPlayerId))

        assertThat(executed).isFalse()
        assertThat(result).isInstanceOf(CommandResult.Failure::class.java)
        val failure = result as CommandResult.Failure
        assertThat(failure.messageKey).isEqualTo(MessageKey("astera.permission.denied"))
        assertThat(failure.placeholders["permission"]).isEqualTo(testPermission.id)
    }

    @Test
    fun `requirePermission - null sender with consoleBypass true - executor invoked`() = runBlocking {
        var executed = false
        val spec = CommandSpec.Literal(
            name = "give",
            subcommands = emptyList(),
            executor = { CommandResult.Success.also { executed = true } },
        ).requirePermission(fakePermissionService(grants = false), testPermission, consoleBypass = true)

        val result = spec.executor!!(ctx(sender = null))

        assertThat(executed).isTrue()
        assertThat(result).isEqualTo(CommandResult.Success)
    }

    @Test
    fun `requirePermission - null sender with consoleBypass false - failure`() = runBlocking {
        var executed = false
        val spec = CommandSpec.Literal(
            name = "give",
            subcommands = emptyList(),
            executor = { CommandResult.Success.also { executed = true } },
        ).requirePermission(fakePermissionService(grants = false), testPermission, consoleBypass = false)

        val result = spec.executor!!(ctx(sender = null))

        assertThat(executed).isFalse()
        assertThat(result).isInstanceOf(CommandResult.Failure::class.java)
        val failure = result as CommandResult.Failure
        assertThat(failure.messageKey).isEqualTo(MessageKey("astera.permission.denied"))
    }
}
