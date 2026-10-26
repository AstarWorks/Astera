package com.astarworks.astera.core.application.command

import com.astarworks.astera.core.domain.permission.Permission

/**
 * Declare the permission node of a [CommandSpec.Literal] (ADR-0184).
 *
 * The node is enforced centrally — by the Brigadier `requires` filter (hidden
 * from completion) and by [CommandInvoker] (checked again before the executor
 * runs) — so executors never check permissions themselves.
 *
 * Scheme: `astera.<area>.<action>`; [CommandAccess.OPERATOR] (default) for admin
 * nodes, [CommandAccess.EVERYONE] for basic player commands.
 *
 * ```kotlin
 * CommandSpec.Literal(name = "give", ...)
 *     .requirePermission(Permission("astera.items.give"))
 * ```
 */
public fun CommandSpec.Literal.requirePermission(
    permission: Permission,
    access: CommandAccess = CommandAccess.OPERATOR,
): CommandSpec.Literal = copy(permission = permission, access = access)

/** Shorthand for [requirePermission] with [CommandAccess.EVERYONE] (basic player commands). */
public fun CommandSpec.Literal.forEveryone(permission: Permission): CommandSpec.Literal =
    requirePermission(permission, CommandAccess.EVERYONE)
