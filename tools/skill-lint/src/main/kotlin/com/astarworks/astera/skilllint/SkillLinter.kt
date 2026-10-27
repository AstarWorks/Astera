package com.astarworks.astera.skilllint

import com.astarworks.astera.core.application.skillengine.compose.ComposeOptions
import com.astarworks.astera.core.application.skillengine.compose.SkillComposer
import com.astarworks.astera.core.application.skillengine.load.SkillCompiler
import com.astarworks.astera.core.application.skillengine.load.SkillDecoder
import com.astarworks.astera.core.domain.Result
import java.nio.file.Path

/**
 * skill-lint v2: compose -> decode -> (compile) -> structural rules. Every stage reports errors with a YAML/model
 * path; nothing is checked by looking at pictures or traces. [compiler] is optional (expression and reference
 * checks of the compile stage are reported as `compile` findings when given).
 */
class SkillLinter(
    private val decoder: SkillDecoder,
    private val compiler: SkillCompiler? = null,
    private val rules: Map<String, SkillRule> = SkillRules.all,
) {
    fun lint(root: Path, options: ComposeOptions = ComposeOptions()): LintReport {
        val composer = SkillComposer(root, options)
        return when (val r = composer.composeAll()) {
            is Result.Failure -> LintReport(
                emptyList(),
                r.error.map { LintFinding("compose", Severity.FAIL, it.file, it.path.ifEmpty { "(document)" }, it.reason) },
            )
            is Result.Success -> lintComposed(r.value)
        }
    }

    fun lintComposed(composed: Map<String, com.astarworks.astera.core.application.skillengine.compose.ComposedSkill>): LintReport {
        val findings = ArrayList<LintFinding>()
        for ((id, skill) in composed.toSortedMap()) {
            val treeFindings = TreeRules.presentationMovesFrames(id, skill.tree)
            findings += treeFindings
            val def = when (val d = decoder.decode(skill)) {
                is Result.Failure -> {
                    // A gameplay op in presentation is also a decode error; the tree rule already named it.
                    if (treeFindings.none { it.path == d.error.path }) {
                        findings += LintFinding("decode", Severity.FAIL, id, d.error.path, d.error.message)
                    }
                    continue
                }
                is Result.Success -> d.value
            }
            compiler?.let { c ->
                (c.compile(def) as? Result.Failure)?.let { findings += LintFinding("compile", Severity.FAIL, id, it.error.path, it.error.message) }
            }
            val ctx = SkillCtx(def, skill.tree)
            for ((_, rule) in rules) findings += rule.check(ctx)
        }
        findings += ProjectRules.check(composed)
        return LintReport(composed.keys.sorted(), findings)
    }
}
