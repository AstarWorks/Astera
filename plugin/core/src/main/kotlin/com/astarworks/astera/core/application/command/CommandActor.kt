package com.astarworks.astera.core.application.command

import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Vendor-neutral identity of whoever types a command.
 *
 * @property playerId the player, or `null` for the console / a command block.
 * @property isOperator server operator (Paper `isOp`) or the console.
 * @property platformHas asks the host's own permission system (LuckPerms etc.)
 *   whether the actor holds a node. Defaults to "no".
 */
public data class CommandActor(
    public val playerId: PlayerId?,
    public val isOperator: Boolean = false,
    public val platformHas: (String) -> Boolean = { false },
) {
    public val isConsole: Boolean get() = playerId == null
}

/**
 * Decides who may see and run a [CommandSpec.Literal] (ADR-0184).
 *
 * Rules, in order:
 *  1. a node without [CommandSpec.Literal.permission] is open to everyone;
 *  2. [CommandAccess.EVERYONE] nodes are open to everyone;
 *  3. [CommandAccess.OPERATOR] nodes: console, operators, holders of the node
 *     through Astera roles ([IPermissionService]) or the host permission system.
 *
 * Visibility ([isVisible]) additionally hides area nodes none of whose
 * descendants the actor can use, so tab completion and `/astera help` only show
 * what the sender may run.
 */
public class CommandAuthorizer(private val permissions: () -> IPermissionService) {

    public fun canUse(actor: CommandActor, node: CommandSpec.Literal): Boolean {
        val permission = node.permission ?: return true
        return holds(actor, permission, node.access)
    }

    public fun holds(actor: CommandActor, permission: Permission, access: CommandAccess): Boolean = when {
        access == CommandAccess.EVERYONE -> true
        actor.isConsole || actor.isOperator -> true
        else -> actor.platformHas(permission.id) ||
            actor.playerId?.let { permissions().check(it, permission) } == true
    }

    /** True when [node] is runnable by [actor] or leads to something that is. */
    public fun isVisible(actor: CommandActor, node: CommandSpec.Literal): Boolean {
        if (!canUse(actor, node)) return false
        return node.executor != null || node.literals.any { isVisible(actor, it) }
    }
}
