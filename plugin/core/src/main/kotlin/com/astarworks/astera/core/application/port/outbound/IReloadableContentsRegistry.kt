package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.application.contents.ReloadableContents
import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import java.nio.file.Path

/**
 * Cross-plugin registry of [ReloadableContents] keyed by [ReloadableContents.categoryName]
 * (Wave _infra-2b, ADR-0065).
 *
 * Each content type owner (weapons, skills, stages, …) registers its `Contents` at
 * `onEnable`; Core's `/astera reload <category>` dispatcher looks up the entry by
 * category name and invokes [ReloadableContents.reload]. The substrate's atomic-swap
 * contract is preserved: a malformed YAML aborts only that category's reload, never
 * the dispatcher itself.
 *
 * Two-arg `register` (contents + dir) keeps the dispatcher convention-agnostic —
 * most categories follow `content/<categoryName>/`, but a category may pass any
 * dir the plugin chose at boot.
 */
public interface IReloadableContentsRegistry {

    /**
     * Bind [contents] (keyed by its [ReloadableContents.categoryName]) to the
     * on-disk [dir] it should reload from. Returns a [Registration] handle so
     * the owning plugin can detach during `onDisable`.
     *
     * Re-registering the same category name replaces the prior binding (last-write
     * wins); this is intentional for hot-swap scenarios (e.g., persistence overlay).
     */
    public fun register(contents: ReloadableContents, dir: Path): Registration

    /** Snapshot of every category name currently bound. Used for tab-completion. */
    public fun categories(): Set<String>

    /**
     * Dispatch a reload to [categoryName]'s contents. Returns `Success(report)`
     * on a successful all-or-nothing swap (which may still carry `report.failed > 0`
     * iff the swap aborted — `LoadFailed` instead is reserved for the soft-error
     * variant), `Failure(UnknownCategory)` if no contents was registered under
     * that name, and `Failure(LoadFailed)` if the swap aborted with parse failures.
     */
    public fun reload(categoryName: String): Result<ContentLoader.LoadReport, ReloadError>

    /** Handle returned by [register]; call [unregister] to detach. */
    public interface Registration {
        public fun unregister()
    }
}

/**
 * Sealed error hierarchy for [IReloadableContentsRegistry.reload]. Each variant
 * carries a [MessageKey] so command adapters can render localized failure text
 * without leaking dispatcher internals.
 */
public sealed class ReloadError {
    public abstract val messageKey: MessageKey

    /** No contents was registered under the requested category name. */
    public data class UnknownCategory(val name: String) : ReloadError() {
        override val messageKey: MessageKey = MessageKey("astera.command.reload.unknown_category")
    }

    /**
     * The dispatch reached the contents but the all-or-nothing swap aborted —
     * one or more YAML files failed to parse. The previous in-memory state is
     * intact; the operator should fix the offending files and retry.
     */
    public data class LoadFailed(
        val categoryName: String,
        val report: ContentLoader.LoadReport,
    ) : ReloadError() {
        override val messageKey: MessageKey = MessageKey("astera.command.reload.load_failed")
    }
}
