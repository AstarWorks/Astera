package com.astarworks.astera.match.command.admin

import com.astarworks.astera.core.application.command.ArgumentType
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.requirePermission
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.match.application.usecase.validate.ValidateContentReferencesUseCase
import com.astarworks.astera.match.application.usecase.validate.ValidationIssue
import org.slf4j.LoggerFactory

/**
 * `/astera admin validate [category]` (ADR-0122, reshaped by ADR-0184).
 *
 * Drives [ValidateContentReferencesUseCase], replies with a summary plus one
 * line per issue (and logs each at WARN for the log pipeline). The command
 * always succeeds; "fail" is informational: operators read the issue count and
 * act. The use case's `ALL` constant drives tab-completion so new check
 * categories show up automatically.
 *
 * Permission `astera.admin.validate`.
 */
public class AdminValidateCommandSpec(
    private val validate: ValidateContentReferencesUseCase,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun build(): CommandSpec.Literal = CommandSpec.Literal(
        name = "validate",
        subcommands = listOf(
            CommandSpec.Argument(
                name = "category",
                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                completer = { _ -> ValidateContentReferencesUseCase.ALL.toList() },
                optional = true,
            ),
        ),
        executor = { ctx ->
            val category = ctx.optional<String>("category")
            val categories = if (category.isNullOrBlank()) emptySet() else setOf(category)
            val report = validate.validate(categories)

            log.info(
                "/astera admin validate: checked={} unknown={} issues={}",
                report.checkedCategories,
                report.unknownCategories,
                report.issues.size,
            )
            ctx.reply(
                MessageKey("astera.command.reply.admin-validate"),
                mapOf(
                    "checked" to report.checkedCategories.joinToString(", ").ifEmpty { "-" },
                    "issues" to report.issues.size.toString(),
                ),
            )
            report.issues.forEach { issue ->
                val line = when (issue) {
                    is ValidationIssue.UnknownSkillReference ->
                        "item ${issue.itemId.value} -> unknown skill ${issue.missingSkillId.value}"
                }
                log.warn("  {}", line)
                ctx.reply(MessageKey("astera.command.reply.admin-validate-issue"), mapOf("issue" to line))
            }
            if (report.unknownCategories.isNotEmpty()) {
                log.warn("Unknown validate categories ignored: {}", report.unknownCategories)
                ctx.reply(
                    MessageKey("astera.command.reply.admin-validate-unknown"),
                    mapOf("categories" to report.unknownCategories.joinToString(", ")),
                )
            }
            CommandResult.Success
        },
    ).requirePermission(Permission("astera.admin.validate"))
}
