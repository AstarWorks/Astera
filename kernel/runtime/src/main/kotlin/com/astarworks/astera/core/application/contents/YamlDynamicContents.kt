package com.astarworks.astera.core.application.contents

import com.astarworks.astera.core.application.port.outbound.IContentParser
import com.astarworks.astera.core.application.service.ContentLoader
import kotlinx.serialization.DeserializationStrategy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.isDirectory

/**
 * Abstract base for every YAML-backed content registry in Astera.
 *
 * Provides:
 *
 *   - An `AtomicReference<Map<K, V>>` store so concurrent readers always see
 *     a self-consistent snapshot (no half-swapped map during reload).
 *   - [bootstrap] for initial load at `onEnable` (best-effort — failures
 *     are logged and skipped, successful entries register).
 *   - [reload] for hot-swap from `/astera-<plugin> reload` (all-or-nothing —
 *     a single file failure aborts the swap; previous state stays intact).
 *   - [register] / [clear] for programmatic registration (tests, admin
 *     commands, fakes).
 *
 * Subclasses provide [toEntry] to map a parsed config DTO to a `(key, value)`
 * pair. Returning `null` from [toEntry] discards that file silently (used to
 * reject configs that pass the DTO schema but fail a domain invariant — most
 * subclasses just throw `IllegalArgumentException` from `toEntry`, which
 * `ContentLoader` catches and counts as a failed load).
 *
 * Phase 1 (Wave _infra-2a) does not implement value→key reverse indexing or
 * Adventure-based ContentKey types (see ADR-0060 deviations from ADR-0017).
 */
public abstract class YamlDynamicContents<K : Any, V : Any, C : Any>(
    public override val categoryName: String,
    deserializer: DeserializationStrategy<C>,
    parsers: List<IContentParser> = ContentLoader.DEFAULT_PARSERS,
) : Contents<K, V>, ReloadableContents {

    private val store: AtomicReference<Map<K, V>> = AtomicReference(emptyMap())
    private val loader = ContentLoader(parsers, deserializer)

    /**
     * Map a successfully-parsed config to its registry entry. Subclasses
     * typically delegate to a DTO's `toSpec()` factory and pair the result
     * with the domain ID inside it.
     *
     * Returning `null` discards the entry without counting it as a failure
     * (rarely needed — most subclasses throw on invalid configs so
     * `ContentLoader` logs + counts a failed load).
     */
    protected abstract fun toEntry(config: C): Pair<K, V>?

    /**
     * Every registry entry of a parsed config. Defaults to the single [toEntry]; override when one file registers several
     * entries (e.g. one per palette variant).
     */
    protected open fun toEntries(config: C): List<Pair<K, V>> = listOfNotNull(toEntry(config))

    override fun find(key: K): V? = store.get()[key]

    final override fun all(): Collection<V> = store.get().values

    /**
     * Manually add a single entry. Atomic — concurrent readers see either
     * the old map or the old+1 map, never a torn state.
     */
    public fun register(key: K, value: V) {
        while (true) {
            val current = store.get()
            val next = current + (key to value)
            if (store.compareAndSet(current, next)) return
        }
    }

    /** Drop every registered entry. Used by Phase 4 hot-reload semantics tests. */
    public fun clear() {
        store.set(emptyMap())
    }

    /**
     * Best-effort initial load at `onEnable`. Files that parse register;
     * files that fail are logged by `ContentLoader` and counted in
     * [ContentLoader.LoadReport.failed]. Existing in-memory state is
     * preserved across calls (e.g., if [register] was called before
     * [bootstrap], those entries survive the load).
     */
    public fun bootstrap(dir: Path): ContentLoader.LoadReport {
        val incoming = LinkedHashMap<K, V>()
        val report = loadAll(dir) { cfg ->
            toEntries(cfg).forEach { (k, v) -> incoming[k] = v }
        }
        while (true) {
            val current = store.get()
            val next = current + incoming
            if (store.compareAndSet(current, next)) break
        }
        return report
    }

    /**
     * Atomic, all-or-nothing reload. On `failed > 0` the previous state
     * remains untouched and the report is returned so the operator can
     * fix the offending YAMLs and retry. On `failed == 0` the new map
     * replaces the old in one atomic operation, unless it is empty while the
     * store is not: that is reported as a failure and the old store is kept.
     */
    final override fun reload(dir: Path): ContentLoader.LoadReport {
        val next = LinkedHashMap<K, V>()
        val report = loadAll(dir) { cfg ->
            toEntries(cfg).forEach { (k, v) -> next[k] = v }
        }
        if (report.failed != 0) return report
        EmptyReloadGuard.check(categoryName, dir, next.size, store.get().size)?.let { return it }
        store.set(next.toMap())
        return report
    }

    /**
     * `true` when the category keeps one YAML per entry in per-category subdirectories (`sounds/ui/click.yaml`):
     * [bootstrap] and [reload] then read [dir] and each direct subdirectory alike, so a reload sees what the
     * bootstrap saw instead of replacing the registry with the root files only.
     */
    protected open val readsSubdirectories: Boolean = false

    private fun loadAll(dir: Path, onLoaded: (C) -> Unit): ContentLoader.LoadReport {
        val dirs = if (readsSubdirectories && dir.isDirectory()) {
            listOf(dir) + Files.list(dir).use { stream -> stream.filter { it.isDirectory() }.sorted().toList() }
        } else {
            listOf(dir)
        }
        var loaded = 0
        var failed = 0
        val failures = mutableListOf<ContentLoader.LoadFailure>()
        for (d in dirs) {
            val r = loader.loadFrom(d, onLoaded)
            loaded += r.loaded
            failed += r.failed
            failures += r.failures
        }
        return ContentLoader.LoadReport(loaded = loaded, failed = failed, failures = failures)
    }
}
