package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.command.CommandActor
import com.astarworks.astera.core.application.command.CommandAuthorizer
import com.astarworks.astera.core.application.command.CommandInvoker
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import kotlinx.coroutines.runBlocking

/**
 * Runs one command node the way the server does (ADR-0184): authorizer gate, then
 * executor, with replies captured. Lets unit tests assert permission gating without
 * booting a registry or Brigadier.
 */
public class NodeRun(
    public val result: CommandResult,
    public val replies: List<Pair<MessageKey, Map<String, String>>>,
)

public fun CommandSpec.Literal.invokeAs(
    permissions: IPermissionService,
    actor: CommandActor,
    args: Map<String, Any> = emptyMap(),
): NodeRun {
    val replies = mutableListOf<Pair<MessageKey, Map<String, String>>>()
    val invoker = CommandInvoker(CommandAuthorizer { permissions }, FakeScheduler())
    val result = runBlocking { invoker.invoke(this@invokeAs, actor, args) { key, ph -> replies += key to ph } }
    return NodeRun(result, replies)
}

/** A plain (non-operator) player. */
public fun playerActor(id: PlayerId): CommandActor = CommandActor(id)

/** The server console. */
public fun consoleActor(): CommandActor = CommandActor(playerId = null, isOperator = true)
