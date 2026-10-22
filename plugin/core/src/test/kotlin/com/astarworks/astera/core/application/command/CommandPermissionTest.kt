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

/** Permission declaration (`requirePermission`) + enforcement (`CommandAuthorizer`, `CommandInvoker`), ADR-0184. */
class CommandPermissionTest {

    private fun permissionService(grants: Boolean): IPermissionService =
        object : IPermissionService {
            override fun assign(playerId: PlayerId, roleId: RoleId): Result<Unit, AssignRoleError> = Result.success(Unit)
            override fun revoke(playerId: PlayerId, roleId: RoleId): Result<Unit, RevokeRoleError> = Result.success(Unit)
            override fun check(playerId: PlayerId, permission: Permission): Boolean = grants
            override fun rolesOf(playerId: PlayerId): Set<RoleId> = emptySet()
            override fun definitionOf(roleId: RoleId): RoleDefinition? = null
            override fun allDefinitions(): Collection<RoleDefinition> = emptyList()
        }

    private val noOpHandle = object : IScheduler.TaskHandle {
        override fun cancel() = Unit
        override val isActive: Boolean get() = false
    }

    private val scheduler: IScheduler = object : IScheduler {
        override fun currentTick(): Long = 0L
        override fun runLater(delay: Duration, block: () -> Unit): IScheduler.TaskHandle = noOpHandle
        override fun runRepeating(initialDelay: Duration, period: Duration, block: () -> Unit): IScheduler.TaskHandle = noOpHandle
        override fun runAsync(block: () -> Unit): IScheduler.TaskHandle = noOpHandle
        override fun scheduleAt(tickAbsolute: Long, block: () -> Unit): IScheduler.TaskHandle = noOpHandle
        override suspend fun awaitTicks(ticks: Long) = Unit
        override val mainDispatcher: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val asyncDispatcher: CoroutineDispatcher get() = Dispatchers.Unconfined
    }

    private val permission = Permission("astera.items.give")
    private val player = PlayerId(UUID.randomUUID())
    private val console = CommandActor(playerId = null, isOperator = true)

    private fun leaf(access: CommandAccess = CommandAccess.OPERATOR, ran: () -> Unit = {}): CommandSpec.Literal =
        CommandSpec.Literal(
            name = "give",
            executor = { CommandResult.Success.also { ran() } },
        ).requirePermission(permission, access)

    private fun invoke(spec: CommandSpec.Literal, grants: Boolean, actor: CommandActor): CommandResult {
        val invoker = CommandInvoker(CommandAuthorizer { permissionService(grants) }, scheduler)
        return runBlocking { invoker.invoke(spec, actor, emptyMap()) { _, _ -> } }
    }

    @Test
    fun `requirePermission records node and operator default`() {
        val spec = leaf()
        assertThat(spec.permission).isEqualTo(permission)
        assertThat(spec.access).isEqualTo(CommandAccess.OPERATOR)
    }

    @Test
    fun `holder of the node runs the executor`() {
        var ran = false
        val result = invoke(leaf { ran = true }, grants = true, actor = CommandActor(player))
        assertThat(ran).isTrue()
        assertThat(result).isEqualTo(CommandResult.Success)
    }

    @Test
    fun `non holder is denied with the permission key and the executor does not run`() {
        var ran = false
        val result = invoke(leaf { ran = true }, grants = false, actor = CommandActor(player))
        assertThat(ran).isFalse()
        val failure = result as CommandResult.Failure
        assertThat(failure.messageKey).isEqualTo(MessageKey("astera.permission.denied"))
        assertThat(failure.placeholders["permission"]).isEqualTo(permission.id)
    }

    @Test
    fun `operator and console pass without holding the node`() {
        assertThat(invoke(leaf(), grants = false, actor = CommandActor(player, isOperator = true)))
            .isEqualTo(CommandResult.Success)
        assertThat(invoke(leaf(), grants = false, actor = console)).isEqualTo(CommandResult.Success)
    }

    @Test
    fun `host permission node lets a non operator in`() {
        val actor = CommandActor(player, platformHas = { it == permission.id })
        assertThat(invoke(leaf(), grants = false, actor = actor)).isEqualTo(CommandResult.Success)
    }

    @Test
    fun `EVERYONE access needs no grant`() {
        assertThat(invoke(leaf(CommandAccess.EVERYONE), grants = false, actor = CommandActor(player)))
            .isEqualTo(CommandResult.Success)
    }

    @Test
    fun `visibility hides an area whose children are all unusable`() {
        val authorizer = CommandAuthorizer { permissionService(grants = false) }
        val area = CommandSpec.Literal(name = "admin", subcommands = listOf(leaf()))
        assertThat(authorizer.isVisible(CommandActor(player), area)).isFalse()
        assertThat(authorizer.isVisible(CommandActor(player, isOperator = true), area)).isTrue()
    }

    @Test
    fun `an executor that throws becomes the internal error reply`() {
        val spec = CommandSpec.Literal(name = "boom", executor = { error("nope") })
            .requirePermission(permission, CommandAccess.EVERYONE)
        val result = invoke(spec, grants = false, actor = CommandActor(player))
        assertThat((result as CommandResult.Failure).messageKey).isEqualTo(MessageKey("astera.command.internal_error"))
    }
}
