package com.astarworks.astera.match.application.usecase.validate

import com.astarworks.astera.combat.application.port.outbound.ISkillRegistry
import com.astarworks.astera.combat.application.service.MechanicSupport
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.PhaseOverrides
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
import com.astarworks.astera.core.domain.skill.phase.PhaseMechanics

/**
 * Wave E 5admin-ops (ADR-0122) — cross-content integrity check.
 *
 * The `/astera-validate [category]` command consumes this use case to
 * report dangling references in authored content. Phase 1 checks only the
 * single cross-plugin reference that exists today:
 *
 *   - **items → skills**: every [com.astarworks.astera.core.domain.item.ItemSkillBinding]
 *     references a [SkillId] that must exist in [ISkillRegistry].
 *
 *   - **skills / item bindings → runtime support**: no skill phase (or item
 *     binding phase override) may use a mechanic, condition, targeter or CEL
 *     script that `MechanicDispatcher` cannot execute yet (see
 *     [MechanicSupport]). Such content would otherwise silently do nothing.
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
     * stable identifiers a `/astera-validate <category>` invocation accepts:
     *
     *   - `"items"` — items → skills (item bindings reference known skills)
     *   - `"skills"` — skills + item phase overrides only use runtime-supported mechanics
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
        if ("skills" in toRun) {
            issues += validateSkillSupport()
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

    private fun validateSkillSupport(): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        skills.all().forEach { skill ->
            phaseMechanicsOf(skill.phases).forEach { mechanic ->
                MechanicSupport.problemsOf(mechanic).forEach { problem ->
                    issues += ValidationIssue.UnsupportedContent("skill ${skill.id.value}", problem)
                }
            }
        }
        items.all().forEach { spec ->
            spec.skillBindings.forEach { binding ->
                overrideMechanicsOf(binding.phaseOverrides).forEach { mechanic ->
                    MechanicSupport.problemsOf(mechanic).forEach { problem ->
                        issues += ValidationIssue.UnsupportedContent(
                            "item ${spec.id.value} binding ${binding.skillId.value}",
                            problem,
                        )
                    }
                }
            }
        }
        return issues
    }

    private fun phaseMechanicsOf(phases: PhaseMechanics): List<Mechanic> = when (phases) {
        is PhaseMechanics.Projectile -> phases.cast + phases.hitEntity + phases.hitBlock + phases.miss + phases.expire
        is PhaseMechanics.Melee -> phases.cast + phases.hitEntity + phases.miss
        is PhaseMechanics.Aoe -> phases.cast + phases.hitEntity + phases.expire
        is PhaseMechanics.Buff -> phases.cast + phases.expire + phases.removed
        is PhaseMechanics.Teleport -> phases.cast + phases.preTeleport + phases.postTeleport
        is PhaseMechanics.Summon -> phases.cast + phases.spawn + phases.summonDeath
    }

    private fun overrideMechanicsOf(o: PhaseOverrides): List<Mechanic> =
        o.castAppend + o.castPrepend + o.castReplace.orEmpty() +
            o.hitEntityAppend + o.hitEntityPrepend + o.hitEntityReplace.orEmpty() +
            o.hitBlockAppend + o.hitBlockPrepend + o.hitBlockReplace.orEmpty() +
            o.missAppend + o.missPrepend + o.missReplace.orEmpty() +
            o.expireAppend + o.expirePrepend + o.expireReplace.orEmpty() +
            o.removedAppend + o.removedPrepend + o.removedReplace.orEmpty() +
            o.preTeleportAppend + o.preTeleportPrepend + o.preTeleportReplace.orEmpty() +
            o.postTeleportAppend + o.postTeleportPrepend + o.postTeleportReplace.orEmpty() +
            o.spawnAppend + o.spawnPrepend + o.spawnReplace.orEmpty() +
            o.summonDeathAppend + o.summonDeathPrepend + o.summonDeathReplace.orEmpty()

    public companion object {
        /** Every category id this use case knows how to check. */
        public val ALL: Set<String> = setOf("items", "skills")
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

    /** Content uses something `MechanicDispatcher` cannot execute (no-op / CEL stub). */
    public data class UnsupportedContent(
        public val owner: String,
        public val problem: String,
    ) : ValidationIssue
}
