package com.astarworks.astera.match.command.admin

import com.astarworks.astera.core.application.command.ArgumentType
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.requirePermission
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.stage.StageId
import org.slf4j.LoggerFactory

/**
 * `/astera admin stage list` and `/astera admin stage select <stage-id>`
 * (ADR-0122, reshaped by ADR-0184).
 *
 * `list` enumerates registered stages from [IStageRegistry]. `select` validates
 * the id exists and records the intent — the "selected next-match stage" state
 * cell is deferred to the matchmaker integration; for now the command gives
 * operators visibility into the stage catalogue.
 *
 * Permission `astera.admin.stage` on both leaves.
 */
public class AdminStageCommandSpec(
    private val stageRegistry: IStageRegistry,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun build(): CommandSpec.Literal = CommandSpec.Literal(
        name = "stage",
        subcommands = listOf(
            CommandSpec.Literal(
                name = "list",
                executor = { ctx ->
                    val stages = stageRegistry.all()
                    log.info("Registered stages ({}): {}", stages.size, stages.map { it.id.value })
                    ctx.reply(
                        MessageKey("astera.command.reply.admin-stage-list"),
                        mapOf(
                            "count" to stages.size.toString(),
                            "stages" to stages.joinToString(", ") { it.id.value }.ifEmpty { "-" },
                        ),
                    )
                    CommandResult.Success
                },
            ).requirePermission(Permission("astera.admin.stage")),
            CommandSpec.Literal(
                name = "select",
                subcommands = listOf(
                    CommandSpec.Argument(
                        name = "stage-id",
                        type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                        completer = { _ -> stageRegistry.all().map { it.id.value } },
                    ),
                ),
                executor = lambda@{ ctx ->
                    val stageStr = ctx.required<String>("stage-id")
                    val stage = runCatching { StageId(stageStr) }.getOrNull()?.let { stageRegistry.find(it) }
                        ?: return@lambda CommandResult.Failure(
                            MessageKey("astera.admin.stage.unknown"),
                            mapOf("stage" to stageStr),
                        )
                    log.info("Stage selected: {} (intent recorded; matchmaker hookup is Wave F)", stage.id.value)
                    ctx.reply(MessageKey("astera.command.reply.admin-stage-select"), mapOf("stage" to stage.id.value))
                    CommandResult.Success
                },
            ).requirePermission(Permission("astera.admin.stage")),
        ),
    )
}
