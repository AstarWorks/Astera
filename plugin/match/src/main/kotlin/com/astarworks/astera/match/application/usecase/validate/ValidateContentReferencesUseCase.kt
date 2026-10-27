package com.astarworks.astera.match.application.usecase.validate

import com.astarworks.astera.combat.application.port.outbound.ISkillRegistry
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.skill.SkillId

/**
 * Wave E 5admin-ops (ADR-0122) — cross-content integrity check.
 *
 * The `/astera admin validate [category]` command consumes this use case to
 * report dangling references in authored content. Phase 1 checks only the
 * single cross-plugin reference that exists today:
 *
 *   - **items → skills**: every [com.astarworks.astera.core.domain.item.ItemSkillBinding]
 *     references a [SkillId] that must exist in [ISkillRegistry].
 *
 * Future categories (stage → world, mission → quest, mob → skill) land as
 * additional check methods on this same use case.
 *
 * The use case is read-only: it never mutates a registry; it returns a
 * [ValidationReport] for the command to render.
 *
 * # Why in `:plugin:match`
 *
 * The check needs both [IItemRegistry] (Core) and [ISkillRegistry] (Combat).
 * Per the sibling-isolation matrix (ADR-0022), Match is the lowest plugin
 * that compileOnly-depends on Combat; placing the use case here keeps Core /
 * Items / Combat free of the cross-cut.
 */
public class ValidateContentReferencesUseCase(
    private val items: IItemRegistry,
    private val skills: ISkillRegistry,
) {

    /**
     * Run every check in [categories] (or all if empty). Categories are the
     * stable identifiers a `/astera admin validate <category>` invocation accepts:
     *
     *   - `"items"` — items → skills (item bindings reference known skills)
     *
     * Unknown category names are reported via [ValidationReport.unknownCategories]
     * rather than throwing, so the command can render a single readout.
     */
    public fun validate(categories: Set<String> = ALL): ValidationReport {
        val toRun = if (categories.isEmpty()) ALL else categories
        val unknown = toRun - ALL
        val issues = mutableListOf<ValidationIssue>()

        if ("items" in toRun) {
            issues += validateItemsSkills()
        }

        return ValidationReport(
            checkedCategories = toRun - unknown,
            unknownCategories = unknown,
            issues = issues.toList(),
        )
    }

    private fun validateItemsSkills(): List<ValidationIssue> {
        val knownSkillIds: Set<SkillId> = skills.all().map { it.id }.toSet()
        val issues = mutableListOf<ValidationIssue>()
        items.all().forEach { spec ->
            spec.skillBindings.forEach { binding ->
                if (binding.skillId !in knownSkillIds) {
                    issues += ValidationIssue.UnknownSkillReference(
                        itemId = spec.id,
                        missingSkillId = binding.skillId,
                    )
                }
            }
        }
        return issues
    }

    public companion object {
        /** Every category id this use case knows how to check. */
        public val ALL: Set<String> = setOf("items")
    }
}

/** Aggregate report from one validation run. */
public data class ValidationReport(
    public val checkedCategories: Set<String>,
    public val unknownCategories: Set<String>,
    public val issues: List<ValidationIssue>,
) {
    public val ok: Boolean get() = issues.isEmpty() && unknownCategories.isEmpty()
}

/** Sealed family of discrete issues a check may surface. */
public sealed interface ValidationIssue {

    public data class UnknownSkillReference(
        public val itemId: ItemId,
        public val missingSkillId: SkillId,
    ) : ValidationIssue
}
