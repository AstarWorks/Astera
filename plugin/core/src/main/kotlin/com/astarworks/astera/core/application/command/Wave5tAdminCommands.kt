package com.astarworks.astera.core.application.command

import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.feedback.IPlayerFeedbackQueue
import com.astarworks.astera.core.application.port.outbound.observability.ISlowQueryAuditRepository
import com.astarworks.astera.core.application.usecase.feedback.SubmitFeedbackUseCase
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.feedback.FeedbackCategory
import com.astarworks.astera.core.domain.feedback.FeedbackId
import com.astarworks.astera.core.domain.feedback.FeedbackStatus
import com.astarworks.astera.core.domain.feedback.FeedbackTriageDecision
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Wave 5t (ADR-0086) — admin / GM / player commands introduced by this wave.
 *
 * Returned as a list of [CommandSpec.Literal] so [com.astarworks.astera.core.platform.AsteraCorePlugin]
 * can splice them into the existing `/astera` root literal as subcommands.
 *
 * Commands:
 *
 *   - `/astera report <target> <reason> [comment]`     player tier  (astera.feedback.submit)
 *   - `/astera feedback list [status]`                  GM tier      (astera.feedback.triage)
 *   - `/astera feedback triage <id> <decision>`         GM tier      (astera.feedback.triage)
 *   - `/astera slow-query report`                       admin tier   (astera.slow_query.report)
 *
 * Brigadier permission gates: each leaf executor wraps with the existing
 * [requirePermission] decorator (`CommandPermission.kt`).
 */
public object Wave5tAdminCommands {

    public fun build(
        permissionService: IPermissionService,
        submitFeedback: SubmitFeedbackUseCase,
        feedbackQueue: IPlayerFeedbackQueue,
        slowQueryRepository: ISlowQueryAuditRepository,
    ): List<CommandSpec.Literal> = listOf(
        reportCommand(permissionService, submitFeedback),
        feedbackCommand(permissionService, feedbackQueue),
        slowQueryCommand(permissionService, slowQueryRepository),
    )

    // ---- /astera report -------------------------------------------------

    private fun reportCommand(
        permissionService: IPermissionService,
        submitFeedback: SubmitFeedbackUseCase,
    ): CommandSpec.Literal {
        val executor: suspend (CommandContext) -> CommandResult = { ctx ->
            val sender = ctx.sender
            if (sender == null) {
                CommandResult.Failure(MessageKey("astera.command.player_only"))
            } else {
                val target = ctx.required<PlayerId>("target")
                val reason = ctx.required<String>("reason")
                val comment = ctx.optional<String>("comment") ?: ""
                val category: FeedbackCategory = when (reason.lowercase()) {
                    "cheating" -> FeedbackCategory.Cheating
                    "bug" -> FeedbackCategory.Bug
                    "harassment" -> FeedbackCategory.Harassment
                    "suggestion" -> FeedbackCategory.Suggestion
                    else -> FeedbackCategory.Other(reason)
                }
                when (val outcome = submitFeedback.submit(sender, target, category, comment)) {
                    is Result.Success -> CommandResult.Success
                    is Result.Failure -> CommandResult.Failure(outcome.error.messageKey)
                }
            }
        }
        return CommandSpec.Literal(
            name = "report",
            description = MessageKey("astera.command.report"),
            subcommands = listOf(
                CommandSpec.Argument(
                    name = "target",
                    type = ArgumentType.PlayerSelector,
                ),
                CommandSpec.Argument(
                    name = "reason",
                    type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                    completer = { _ ->
                        listOf("cheating", "bug", "harassment", "suggestion", "other")
                    },
                ),
                CommandSpec.Argument(
                    name = "comment",
                    type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.Greedy),
                ),
            ),
            executor = executor,
        ).requirePermission(
            permissionService = permissionService,
            permission = Permission("astera.feedback.submit"),
        )
    }

    // ---- /astera feedback list / triage ---------------------------------

    private fun feedbackCommand(
        permissionService: IPermissionService,
        feedbackQueue: IPlayerFeedbackQueue,
    ): CommandSpec.Literal {
        val listLeaf = CommandSpec.Literal(
            name = "list",
            description = MessageKey("astera.command.feedback.list"),
            subcommands = listOf(
                CommandSpec.Argument(
                    name = "status",
                    type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                    completer = { _ ->
                        FeedbackStatus.values().map { it.name.lowercase() }
                    },
                ),
            ),
            executor = { ctx ->
                val statusArg = ctx.optional<String>("status")
                val statusFilter = statusArg?.let { s ->
                    FeedbackStatus.values().firstOrNull { it.name.equals(s, ignoreCase = true) }
                }
                val list = feedbackQueue.list(statusFilter, null, limit = 25)
                list.forEach { fb ->
                    // Wave 5t logs only — rich per-row rendering needs the
                    // broadcaster "send to player" surface (Wave 5b HUD ports /
                    // Wave 6b PaperBroadcaster).
                    println(
                        "[feedback] ${fb.id.value} ${fb.status.name} ${fb.category.key} by=${fb.submittedBy}",
                    )
                }
                CommandResult.Success
            },
        ).requirePermission(
            permissionService = permissionService,
            permission = Permission("astera.feedback.triage"),
        )

        val triageExecutor: suspend (CommandContext) -> CommandResult = { ctx ->
            val sender = ctx.sender
            if (sender == null) {
                CommandResult.Failure(MessageKey("astera.command.player_only"))
            } else {
                val idStr = ctx.required<String>("id")
                val decisionRaw = ctx.required<String>("decision")
                val parsedId = runCatching { FeedbackId(UUID.fromString(idStr)) }.getOrNull()
                if (parsedId == null) {
                    CommandResult.Failure(MessageKey("astera.feedback.unknown_id"))
                } else {
                    val decision: FeedbackTriageDecision? = when (decisionRaw.lowercase()) {
                        "in_progress" -> FeedbackTriageDecision.MarkInProgress
                        "resolve" -> FeedbackTriageDecision.Resolve()
                        "dismiss" -> FeedbackTriageDecision.Dismiss()
                        else -> null
                    }
                    if (decision == null) {
                        CommandResult.Failure(MessageKey("astera.feedback.invalid_transition"))
                    } else {
                        when (val outcome = feedbackQueue.triage(parsedId, decision, sender)) {
                            is Result.Success -> CommandResult.Success
                            is Result.Failure -> CommandResult.Failure(outcome.error.messageKey)
                        }
                    }
                }
            }
        }
        val triageLeaf = CommandSpec.Literal(
            name = "triage",
            description = MessageKey("astera.command.feedback.triage"),
            subcommands = listOf(
                CommandSpec.Argument(
                    name = "id",
                    type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                ),
                CommandSpec.Argument(
                    name = "decision",
                    type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                    completer = { _ -> listOf("in_progress", "resolve", "dismiss") },
                ),
            ),
            executor = triageExecutor,
        ).requirePermission(
            permissionService = permissionService,
            permission = Permission("astera.feedback.triage"),
        )

        return CommandSpec.Literal(
            name = "feedback",
            description = MessageKey("astera.command.feedback"),
            subcommands = listOf(listLeaf, triageLeaf),
        )
    }

    // ---- /astera slow-query report --------------------------------------

    private fun slowQueryCommand(
        permissionService: IPermissionService,
        slowQueryRepository: ISlowQueryAuditRepository,
    ): CommandSpec.Literal {
        val reportLeaf = CommandSpec.Literal(
            name = "report",
            description = MessageKey("astera.command.slow_query.report"),
            executor = { _ ->
                val sevenDaysAgo = Instant.now().minus(7L, ChronoUnit.DAYS)
                val records = slowQueryRepository.findAfter(sevenDaysAgo, limit = 25)
                println(
                    "[slow-query] total=${slowQueryRepository.count()} " +
                        "last7d=${records.size} (showing top ${records.size})",
                )
                records.forEachIndexed { idx, r ->
                    println(
                        "[slow-query] #${idx + 1} ${r.durationMs}ms hash=${r.statementHash} " +
                            "ctx=${r.context} sql=${r.statementSummary.take(120)}",
                    )
                }
                CommandResult.Success
            },
        ).requirePermission(
            permissionService = permissionService,
            permission = Permission("astera.slow_query.report"),
        )

        return CommandSpec.Literal(
            name = "slow-query",
            description = MessageKey("astera.command.slow_query"),
            subcommands = listOf(reportLeaf),
        )
    }
}
