package com.astarworks.astera.match.command.admin

import com.astarworks.astera.core.application.command.ArgumentType
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.requirePermission
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.match.application.usecase.validate.ValidateContentReferencesUseCase
import com.astarworks.astera.match.application.usecase.validate.ValidationIssue
import org.slf4j.LoggerFactory

/**
 * Wave E 5admin-ops (ADR-0122) — `/astera-validate [category]` command.
 *
 * Drives [ValidateContentReferencesUseCase] and logs each issue at WARN so
 * the report lands in the standard log pipeline (Wave 8c log aggregator
 * picks it up). The command always returns [CommandResult.Success]; the
 * "fail" condition is purely informational — operators read the issue
 * count and act.
 *
 * Phase 1 supports only `items` (items → skills); the use case's `ALL`
 * constant drives tab-completion so new check categories show up
 * automatically as future waves extend the validator.
 *
 * Permission `astera.admin.validate`.
 */
public class AdminValidateCommandSpec(
    private val validate: ValidateContentReferencesUseCase,
    private val permissionService: IPermissionService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun build(): CommandSpec.Literal = CommandSpec.Literal(
        name = "astera-validate",
        description = MessageKey("astera.admin.validate.root"),
        subcommands = listOf(
            CommandSpec.Argument(
                name = "category",
                type = ArgumentType.StringArg(ArgumentType.StringArg.Mode.SingleWord),
                completer = { _ -> ValidateContentReferencesUseCase.ALL.toList() },
            ),
        ),
        executor = { ctx ->
            val category = ctx.optional<String>("category")
            val categories = if (category.isNullOrBlank()) emptySet() else setOf(category)
            val report = validate.validate(categories)

            log.info(
                "/astera-validate: checked={} unknown={} issues={}",
                report.checkedCategories,
                report.unknownCategories,
                report.issues.size,
            )
            report.issues.forEach { issue ->
                when (issue) {
                    is ValidationIssue.UnknownSkillReference -> log.warn(
                        "  item {} → unknown skill {}",
                        issue.itemId.value,
                        issue.missingSkillId.value,
                    )
                }
            }
            if (report.unknownCategories.isNotEmpty()) {
                log.warn("Unknown validate categories ignored: {}", report.unknownCategories)
            }
            CommandResult.Success
        },
    ).requirePermission(
        permissionService = permissionService,
        permission = Permission("astera.admin.validate"),
    )
}
