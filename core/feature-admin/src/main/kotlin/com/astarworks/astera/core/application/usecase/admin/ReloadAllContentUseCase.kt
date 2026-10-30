package com.astarworks.astera.core.application.usecase.admin

import com.astarworks.astera.core.application.port.outbound.IReloadableContentsRegistry
import com.astarworks.astera.core.domain.Result

/** Outcome of reloading every registered content category. */
public data class ReloadAllSummary(
    val reloaded: List<String>,
    val failed: List<String>,
    /** Reload notes of every category that reported any, each prefixed `category: `. */
    val notes: List<String> = emptyList(),
)

/** `/astera admin panel` server page: at least one category failed to reload (previous state is preserved per category). */
public data class ReloadAllFailed(val summary: ReloadAllSummary)

/**
 * Reloads every category registered on [IReloadableContentsRegistry] — the same
 * per-category `reload()` that `/astera admin reload <category>` calls, run for all
 * of them (F1-D, ADR-0183). Categories are independent: a failure in one never
 * stops the others.
 */
public class ReloadAllContentUseCase(
    private val registry: IReloadableContentsRegistry,
) {
    public fun execute(): Result<ReloadAllSummary, ReloadAllFailed> {
        val ok = mutableListOf<String>()
        val bad = mutableListOf<String>()
        val notes = mutableListOf<String>()
        for (category in registry.categories().sorted()) {
            when (val outcome = registry.reload(category)) {
                is Result.Success -> {
                    ok += category
                    outcome.value.notes.forEach { notes += "$category: $it" }
                }
                is Result.Failure -> bad += category
            }
        }
        val summary = ReloadAllSummary(reloaded = ok, failed = bad, notes = notes)
        return if (bad.isEmpty()) Result.success(summary) else Result.failure(ReloadAllFailed(summary))
    }
}
