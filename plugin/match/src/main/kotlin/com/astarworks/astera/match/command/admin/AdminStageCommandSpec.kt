package com.astarworks.astera.match.command.admin

import com.astarworks.astera.core.application.command.ArgumentType
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.requirePermission
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.stage.StageId
import org.slf4j.LoggerFactory

/**
 * Wave E 5admin-ops (ADR-0122) — `/astera-stage <list|select> [stage-id]`.
 *
 * `list` enumerates registered stages from [IStageRegistry] for tab-completion
 * + readout. `select <stage-id>` validates the id exists and logs the
 * selection — the actual "selected next-match stage" state cell is deferred to
 * Wave F (matchmaker integration); for now the command provides operator
 * visibility into the stage catalogue and a low-risk audit trail of intent.
 *
 * Permission `astera.admin.stage`.
 */
public class AdminStageCommandSpec(
    private val stageRegistry: IStageRegistry,
    private val permissionService: IPermissionService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun build(): CommandSpec.Literal = CommandSpec.Literal(
        name = "astera-stage",
        description = MessageKey("astera.admin.stage.root"),
        subcommands = listOf(
            CommandSpec.Argument(
                name = "action",
                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                completer = { _ -> listOf("list", "select") },
            ),
            CommandSpec.Argument(
                name = "stage-id",
                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                completer = { _ ->
                    stageRegistry.all().map { it.id.value }
                },
            ),
        ),
        executor = { ctx ->
            val action = ctx.required<String>("action").lowercase()
            when (action) {
                "list" -> {
                    val stages = stageRegistry.all()
                    log.info("Registered stages ({}): {}", stages.size, stages.map { it.id.value })
                    CommandResult.Success
                }
                "select" -> {
                    val stageStr = ctx.optional<String>("stage-id")
                        ?: return@Literal CommandResult.Failure(
                            MessageKey("astera.admin.stage.missing_id"),
                        )
                    val stage = stageRegistry.find(StageId(stageStr))
                        ?: return@Literal CommandResult.Failure(
                            MessageKey("astera.admin.stage.unknown"),
                            mapOf("stage" to stageStr),
                        )
                    log.info("Stage selected: {} (intent recorded; matchmaker hookup is Wave F)", stage.id.value)
                    CommandResult.Success
                }
                else -> CommandResult.Failure(
                    MessageKey("astera.admin.stage.unknown_action"),
                    mapOf("action" to action),
                )
            }
        },
    ).requirePermission(
        permissionService = permissionService,
        permission = Permission("astera.admin.stage"),
    )
}
