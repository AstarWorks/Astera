package com.astarworks.astera.core.application.port.outbound.admin

import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import java.nio.file.Path

/**
 * Wave 5t (ADR-0086) — Admin/Ops surface for "edit a YAML/JSON file at runtime,
 * then reload the affected category atomically".
 *
 * Wraps the existing `IReloadableContentsRegistry` (ADR-0065): the registry
 * knows which categories exist + how to atomically swap them, but it can't
 * "edit and reload" a single file. [ILiveConfigEditor] adds that small piece
 * so admin tooling (in-game `/astera config edit …`, or a future Web UI) can
 * do a one-shot edit cycle without leaving the server console.
 *
 * The edit is a **text-replace at a known path**. The editor:
 *
 *   1. Verifies [path] is under the category's bound directory (no path
 *      escape; rejects `../` etc.).
 *   2. Writes [newContent] to that path atomically (write to `*.tmp` →
 *      `Files.move` with `ATOMIC_MOVE` + `REPLACE_EXISTING`).
 *   3. Asks the registry to reload the affected category. If the reload aborts
 *      (parse error), the on-disk file is left in its newly-written state so
 *      the operator can re-edit and retry; the in-memory state is preserved
 *      via the substrate's all-or-nothing contract.
 *
 * Concurrency: every call is single-threaded relative to the registry's own
 * reload contract (one category at a time). Concurrent edits across different
 * categories are independent.
 */
public interface ILiveConfigEditor {

    /**
     * Apply [newContent] to [path] under [categoryName], then reload that
     * category atomically. Success carries the load report; failures carry a
     * structured error with a [MessageKey] for localized command feedback.
     */
    public fun edit(
        categoryName: String,
        path: Path,
        newContent: String,
    ): Result<ContentLoader.LoadReport, LiveConfigEditError>

    /**
     * Read the current on-disk contents of [path] under [categoryName] without
     * mutating anything. Useful for an admin tool's "edit" flow: read → present
     * → user edits → [edit] back.
     */
    public fun read(
        categoryName: String,
        path: Path,
    ): Result<String, LiveConfigEditError>
}

/**
 * Sealed errors for [ILiveConfigEditor]. Each carries a [MessageKey] so command
 * adapters can render localized text without leaking I/O internals.
 */
public sealed class LiveConfigEditError {
    public abstract val messageKey: MessageKey

    /** No category was registered under the given name. */
    public data class UnknownCategory(val name: String) : LiveConfigEditError() {
        override val messageKey: MessageKey = MessageKey("astera.admin.config.unknown_category")
    }

    /** The requested file is not under the category's bound directory. */
    public data class PathEscaped(val path: Path, val categoryRoot: Path) : LiveConfigEditError() {
        override val messageKey: MessageKey = MessageKey("astera.admin.config.path_escaped")
    }

    /** Underlying I/O failure (file system, permissions, …). */
    public data class IoFailed(val reason: String) : LiveConfigEditError() {
        override val messageKey: MessageKey = MessageKey("astera.admin.config.io_failed")
    }

    /** The atomic reload aborted with parse failures (state preserved). */
    public data class ReloadAborted(
        val categoryName: String,
        val report: ContentLoader.LoadReport,
    ) : LiveConfigEditError() {
        override val messageKey: MessageKey = MessageKey("astera.admin.config.reload_aborted")
    }
}
