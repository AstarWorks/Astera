package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.contents.ReloadableContents
import com.astarworks.astera.core.application.port.outbound.IReloadableContentsRegistry
import com.astarworks.astera.core.application.port.outbound.ReloadError
import com.astarworks.astera.core.domain.Result
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Default [IReloadableContentsRegistry] (Wave _infra-2b, ADR-0065).
 *
 * In-process `ConcurrentHashMap` keyed by [ReloadableContents.categoryName].
 * Phase 1 PoC: a single impl, no priority overlay (unlike most other ports —
 * categories are themselves the namespace, so a persistence overlay registers
 * a different `ReloadableContents` under the same name rather than a parallel
 * impl).
 */
public class InMemoryReloadableContentsRegistry : IReloadableContentsRegistry {

    private data class Entry(val contents: ReloadableContents, val dir: Path)

    /**
     * Newest binding last. A rewire (ADR-0311) binds the new generation of a category before the old one is
     * unbound, so a name can briefly hold two entries; the newest answers, and a rolled-back binding uncovers the old.
     */
    private val entries = ConcurrentHashMap<String, List<Entry>>()

    override fun register(
        contents: ReloadableContents,
        dir: Path,
    ): IReloadableContentsRegistry.Registration {
        val name = contents.categoryName
        val entry = Entry(contents, dir)
        entries.merge(name, listOf(entry)) { old, added -> old + added }
        return object : IReloadableContentsRegistry.Registration {
            override fun unregister() {
                entries.computeIfPresent(name) { _, list ->
                    list.filterNot { it === entry }.ifEmpty { null }
                }
            }
        }
    }

    override fun categories(): Set<String> = entries.keys.toSet()

    override fun reload(categoryName: String): Result<ContentLoader.LoadReport, ReloadError> {
        val entry = entries[categoryName]?.lastOrNull()
            ?: return Result.Failure(ReloadError.UnknownCategory(categoryName))
        val report = entry.contents.reload(entry.dir)
        return if (report.failed == 0) {
            Result.Success(report)
        } else {
            Result.Failure(ReloadError.LoadFailed(categoryName, report))
        }
    }
}
