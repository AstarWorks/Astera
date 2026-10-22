package com.astarworks.astera.core.application.command

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
 * Wave 5t (ADR-0086) feedback / diagnostics commands, in the single `/astera`
 * tree (ADR-0184):
 *
 *   - `/astera report <target> <reason> [comment...]`   player tier (astera.feedback.submit)
 *   - `/astera admin feedback list [status]`            GM tier     (astera.feedback.triage)
 *   - `/astera admin feedback triage <id> <decision>`   GM tier     (astera.feedback.triage)
 *   - `/astera admin slow-query report`                 admin tier  (astera.slow_query.report)
 */
public object Wave5tAdminCommands {

    /** Mount all of them: `report` at the root, the rest below `admin`. */
    public fun mount(
        commands: CommandMount,
        submitFeedback: SubmitFeedbackUseCase,
        feedbackQueue: IPlayerFeedbackQueue,
        slowQueryRepository: ISlowQueryAuditRepository,
    ) {
        commands.register(reportCommand(submitFeedback))
        commands.register("admin", feedbackCommand(feedbackQueue))
        commands.register("admin", slowQueryCommand(slowQueryRepository))
    }

    private fun reportCommand(submitFeedback: SubmitFeedbackUseCase): CommandSpec.Literal =
        CommandSpec.Literal(
            name = "report",
            subcommands = listOf(
                CommandSpec.Argument(name = "target", type = ArgumentType.PlayerSelector),
                CommandSpec.Argument(
                    name = "reason",
                    type = ArgumentType.Choice("cheating", "bug", "harassment", "suggestion", "other"),
                ),
                CommandSpec.Argument(
                    name = "comment",
                    type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.Greedy),
                    optional = true,
                ),
            ),
            executor = { ctx ->
                val sender = ctx.sender
                    ?: return@Literal CommandResult.Failure(MessageKey("astera.command.player-only"))
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
                    is Result.Success -> {
                        ctx.reply(MessageKey("astera.command.reply.report"), mapOf("reason" to reason))
                        CommandResult.Success
                    }
                    is Result.Failure -> CommandResult.Failure(outcome.error.messageKey)
                }
            },
        ).requirePermission(Permission("astera.feedback.submit"), CommandAccess.EVERYONE)

    private fun feedbackCommand(feedbackQueue: IPlayerFeedbackQueue): CommandSpec.Literal {
        val listLeaf = CommandSpec.Literal(
            name = "list",
            subcommands = listOf(
                CommandSpec.Argument(
                    name = "status",
                    type = ArgumentType.Choice(FeedbackStatus.values().map { it.name.lowercase() }),
                    optional = true,
                ),
            ),
            executor = { ctx ->
                val statusFilter = ctx.optional<String>("status")?.let { s ->
                    FeedbackStatus.values().firstOrNull { it.name.equals(s, ignoreCase = true) }
                }
                val list = feedbackQueue.list(statusFilter, null, limit = 25)
                ctx.reply(MessageKey("astera.command.reply.admin-feedback-list-header"), mapOf("count" to list.size.toString()))
                list.forEach { fb ->
                    ctx.reply(
                        MessageKey("astera.command.reply.admin-feedback-list-line"),
                        mapOf(
                            "id" to fb.id.value.toString(),
                            "status" to fb.status.name,
                            "category" to fb.category.key,
                            "by" to fb.submittedBy.toString(),
                        ),
                    )
                }
                CommandResult.Success
            },
        ).requirePermission(Permission("astera.feedback.triage"))

        val triageLeaf = CommandSpec.Literal(
            name = "triage",
            subcommands = listOf(
                CommandSpec.Argument(name = "id", type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord)),
                CommandSpec.Argument(name = "decision", type = ArgumentType.Choice("in_progress", "resolve", "dismiss")),
            ),
            executor = { ctx ->
                val sender = ctx.sender
                    ?: return@Literal CommandResult.Failure(MessageKey("astera.command.player-only"))
                val idStr = ctx.required<String>("id")
                val decisionRaw = ctx.required<String>("decision")
                val parsedId = runCatching { FeedbackId(UUID.fromString(idStr)) }.getOrNull()
                    ?: return@Literal CommandResult.Failure(MessageKey("astera.feedback.unknown_id"))
                val decision: FeedbackTriageDecision = when (decisionRaw.lowercase()) {
                    "in_progress" -> FeedbackTriageDecision.MarkInProgress
                    "resolve" -> FeedbackTriageDecision.Resolve()
                    "dismiss" -> FeedbackTriageDecision.Dismiss()
                    else -> return@Literal CommandResult.Failure(MessageKey("astera.feedback.invalid_transition"))
                }
                when (val outcome = feedbackQueue.triage(parsedId, decision, sender)) {
                    is Result.Success -> {
                        ctx.reply(
                            MessageKey("astera.command.reply.admin-feedback-triage"),
                            mapOf("id" to idStr, "decision" to decisionRaw),
                        )
                        CommandResult.Success
                    }
                    is Result.Failure -> CommandResult.Failure(outcome.error.messageKey)
                }
            },
        ).requirePermission(Permission("astera.feedback.triage"))

        return CommandSpec.Literal(name = "feedback", subcommands = listOf(listLeaf, triageLeaf))
    }

    private fun slowQueryCommand(slowQueryRepository: ISlowQueryAuditRepository): CommandSpec.Literal {
        val reportLeaf = CommandSpec.Literal(
            name = "report",
            executor = { ctx ->
                val sevenDaysAgo = Instant.now().minus(7L, ChronoUnit.DAYS)
                val records = slowQueryRepository.findAfter(sevenDaysAgo, limit = 25)
                ctx.reply(
                    MessageKey("astera.command.reply.admin-slow-query-header"),
                    mapOf("total" to slowQueryRepository.count().toString(), "recent" to records.size.toString()),
                )
                records.forEachIndexed { idx, r ->
                    ctx.reply(
                        MessageKey("astera.command.reply.admin-slow-query-line"),
                        mapOf(
                            "index" to (idx + 1).toString(),
                            "ms" to r.durationMs.toString(),
                            "context" to r.context.ifEmpty { "-" },
                            "sql" to r.statementSummary.take(120),
                        ),
                    )
                }
                CommandResult.Success
            },
        ).requirePermission(Permission("astera.slow_query.report"))

        return CommandSpec.Literal(name = "slow-query", subcommands = listOf(reportLeaf))
    }
}
