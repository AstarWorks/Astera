package com.astarworks.astera.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import com.lemonappdev.konsist.api.provider.KoBaseProvider
import com.lemonappdev.konsist.api.verify.assertFalse as konsistAssertFalse

/**
 * Shared Konsist scope factories + a non-vacuous `assertFalse`.
 *
 * Why this exists:
 *
 *  1. `Konsist.scopeFromModule("plugin/core")` / `scopeFromProject()` match every
 *     `.../plugin/core/src/...` path under the project root. Agent git worktrees
 *     live in `<repo>/.claude/worktrees/<name>/` and hold full copies of the code,
 *     so a run from the main checkout scanned N+1 copies (heap exhaustion, and
 *     violations reported from throw-away copies). Every scope here is built from
 *     explicit source directories and then verified to contain nothing under
 *     `/.claude/`.
 *  2. A rule whose scope silently resolves to zero files is a rule that checks
 *     nothing (`plugin/weapons` was renamed to `plugin/items` and its rules went
 *     vacuous). [moduleScope] fails on an empty module and [assertFalse] fails on
 *     an empty subject list; use [assertFalseAllowEmpty] only where emptiness is
 *     legitimate and say why at the call site.
 */
internal object KonsistScopes {

    /** Top-level directories that hold Kotlin sources of the project (what `scopeFromProject()` covers). */
    private val PROJECT_ROOTS = listOf("plugin", "tools")

    /** Parsed once; every module scope is a slice of it so the PSI trees are shared (heap). */
    private val projectScope: KoScope by lazy {
        val scope = Konsist.scopeFromDirectories(PROJECT_ROOTS).slice { relativePath(it.path).contains("/src/") }
        checkScope(scope, "project")
        scope
    }

    fun module(module: String): KoScope {
        val prefix = "$module/src/"
        val scope = projectScope.slice { relativePath(it.path).startsWith(prefix) }
        checkScope(scope, "module '$module'")
        return scope
    }

    fun project(): KoScope = projectScope

    /** Path relative to the Konsist project root (this checkout may itself sit inside `.claude/worktrees/`). */
    private fun relativePath(path: String): String = path.removePrefix(Konsist.projectRootPath.trimEnd('/') + "/")

    private fun checkScope(scope: KoScope, label: String) {
        val files = scope.files
        check(files.isNotEmpty()) {
            "Konsist scope for $label resolved to zero files - the rule would check nothing. " +
                "Was the module renamed or moved?"
        }
        // Only *nested* copies are a leak (see relativePath).
        val leaked = files.map { relativePath(it.path) }.filter { it.contains(".claude/") }
        check(leaked.isEmpty()) {
            "Konsist scope for $label leaked ${leaked.size} file(s) from /.claude/ (agent worktrees), " +
                "e.g. ${leaked.first()}"
        }
    }
}

internal fun moduleScope(module: String): KoScope = KonsistScopes.module(module)

internal fun projectScope(): KoScope = KonsistScopes.project()

/** [com.lemonappdev.konsist.api.verify.assertFalse] that fails when there is nothing to check. */
internal fun <E : KoBaseProvider> List<E>.assertFalse(
    strict: Boolean = false,
    testName: String? = null,
    additionalMessage: String? = null,
    function: (E) -> Boolean?,
) {
    check(isNotEmpty()) {
        "Konsist rule matched zero declarations (vacuous pass). Fix its scope/filter, or use " +
            "assertFalseAllowEmpty with a reason if emptiness is legitimate."
    }
    konsistAssertFalse(strict, testName, additionalMessage, function)
}

/** Explicit opt-out of the non-empty guard; [reason] documents why this rule may legitimately match nothing. */
@Suppress("UNUSED_PARAMETER")
internal fun <E : KoBaseProvider> List<E>.assertFalseAllowEmpty(
    reason: String,
    additionalMessage: String? = null,
    function: (E) -> Boolean?,
) {
    konsistAssertFalse(false, null, additionalMessage, function)
}

/** Guard for hand-rolled `assertThat(violators).isEmpty()` rules: the population under test must not be empty. */
internal fun <T> requireSubjects(subjects: Collection<T>, what: String) {
    check(subjects.isNotEmpty()) {
        "Konsist rule matched zero $what (vacuous pass). Fix its scope/filter."
    }
}
