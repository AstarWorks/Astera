package com.astarworks.astera.core.application.command

import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission

/**
 * Wrap a [CommandSpec.Literal]'s executor so it runs [IPermissionService.check] first.
 *
 * Returns [CommandResult.Failure] with [MessageKey]("astera.permission.denied") when
 * the sender lacks [permission]. Console invocations ([CommandContext.sender] == null)
 * bypass the check when [consoleBypass] is `true` (default), mirroring the
 * convention that the server console is implicitly op-level.
 *
 * Usage:
 * ```kotlin
 * CommandSpec.Literal(name = "give", ...).requirePermission(
 *     permissionService = permService,
 *     permission = Permission("astera.weapons.give"),
 * )
 * ```
 *
 * Because [CommandSpec.Literal] is a `data class`, the underlying executor is replaced
 * via [copy] — all other fields (name, description, subcommands) are preserved intact.
 */
public fun CommandSpec.Literal.requirePermission(
    permissionService: IPermissionService,
    permission: Permission,
    consoleBypass: Boolean = true,
): CommandSpec.Literal {
    val originalExecutor = this.executor ?: return this  // no executor → nothing to gate
    return this.copy(executor = { ctx ->
        val sender = ctx.sender
        when {
            sender == null && consoleBypass -> originalExecutor(ctx)
            sender == null -> CommandResult.Failure(
                MessageKey("astera.permission.denied"),
                mapOf("permission" to permission.id),
            )
            permissionService.check(sender, permission) -> originalExecutor(ctx)
            else -> CommandResult.Failure(
                MessageKey("astera.permission.denied"),
                mapOf("permission" to permission.id),
            )
        }
    })
}
