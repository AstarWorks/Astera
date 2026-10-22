package com.astarworks.astera.core.application.command

import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.i18n.MessageKey
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * The single place where a resolved command node is actually run
 * (ADR-0184). Both the Paper Brigadier bridge and the host-neutral
 * [CommandTreeRunner] call it, so permission enforcement, error mapping and
 * reply rendering cannot diverge between the real server and the simulator.
 *
 *  1. [CommandAuthorizer] gate (denied -> `astera.permission.denied`);
 *  2. the node's executor, with a [CommandContext] whose `reply` reaches the sender;
 *  3. a [CommandResult.Failure] is replied as its message key (the executor
 *     reports every error through this channel);
 *  4. an exception becomes `astera.command.internal_error` and a log line.
 */
public class CommandInvoker(
    private val authorizer: CommandAuthorizer,
    private val scheduler: IScheduler,
) {

    public suspend fun invoke(
        node: CommandSpec.Literal,
        actor: CommandActor,
        args: Map<String, Any>,
        reply: (MessageKey, Map<String, String>) -> Unit,
    ): CommandResult {
        val executor = node.executor
            ?: return CommandResult.Failure(USAGE).also { reply(it.messageKey, it.placeholders) }
        if (!authorizer.canUse(actor, node)) {
            return CommandResult.Failure(
                MessageKey("astera.permission.denied"),
                mapOf("permission" to (node.permission?.id ?: "")),
            ).also { reply(it.messageKey, it.placeholders) }
        }
        val ctx = CommandContext(
            sender = actor.playerId,
            args = args,
            scheduler = scheduler,
            actor = actor,
            replier = reply,
        )
        val result = try {
            executor(ctx)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log.warn("Astera command '{}' failed", node.name, t)
            CommandResult.Failure(MessageKey("astera.command.internal_error"))
        }
        if (result is CommandResult.Failure) reply(result.messageKey, result.placeholders)
        return result
    }

    public companion object {
        public val USAGE: MessageKey = MessageKey("astera.command.usage")
        private val log = LoggerFactory.getLogger(CommandInvoker::class.java)
    }
}
