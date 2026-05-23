package com.astarworks.astera.core.application.service.mob.pve

import com.astarworks.astera.core.application.config.mob.pve.LootTableYamlConfig
import com.astarworks.astera.core.application.port.outbound.IContentParser
import com.astarworks.astera.core.application.port.outbound.mob.ILootRollService
import com.astarworks.astera.core.application.service.ContentLoader
import java.nio.file.Path

/**
 * Loads `content/pve/drop-tables/<id>.yaml` and registers each table with
 * [ILootRollService] (Wave 5pve-content, ADR-0099).
 *
 * Distinct from [com.astarworks.astera.core.application.contents.pve.EncounterZoneContents]
 * because loot tables are not a `Contents<K, V>` shape — each table is a
 * **list** of drops, not a single value. Keeping the loader as a thin
 * orchestrator over [ContentLoader] avoids forcing a fake `Contents` shape
 * onto a fundamentally many-rows-per-key concept.
 *
 * # Bootstrap semantics
 *
 * [bootstrap] mirrors [com.astarworks.astera.core.application.contents.YamlDynamicContents.bootstrap]:
 * best-effort, partial success allowed, failed files counted in the report.
 * Each successful table calls [ILootRollService.registerTable], which
 * replaces any previously-registered drops for that table id (last write
 * wins — consistent with the deterministic impl's `tables[tableId] = drops.toList()`).
 *
 * # Reload semantics
 *
 * Phase 1 ships only [bootstrap]; hot-reload via `/astera reload loot-tables`
 * is a Phase 4 follow-up (would require a per-file old/new diff to avoid
 * leaving stale registrations from removed files). Operators can re-bootstrap
 * after a restart for now.
 *
 * # Why no [com.astarworks.astera.core.application.port.outbound.IReloadableContentsRegistry]
 * registration
 *
 * `IReloadableContentsRegistry` accepts entries that conform to
 * `ReloadableContents`. Loot tables don't fit (no single value per key)
 * and rather than build a façade just for ceremony, we keep loot loading
 * imperative at `onEnable`. The Phase 4 reload story will revisit.
 */
public class LootTableLoaderService(
    parsers: List<IContentParser> = ContentLoader.DEFAULT_PARSERS,
) {

    private val loader = ContentLoader(parsers, LootTableYamlConfig.serializer())

    /**
     * Best-effort initial load. Reads every `.yaml` / `.yml` file in [dir]
     * (or returns a no-files report if the directory doesn't exist),
     * registering each successful table with [rollService]. Failed files
     * are logged by [ContentLoader] and counted in the returned report.
     *
     * @return [ContentLoader.LoadReport] with `loaded` + `failed` counts.
     */
    public fun bootstrap(dir: Path, rollService: ILootRollService): ContentLoader.LoadReport {
        return loader.loadFrom(dir) { cfg ->
            val (tableId, drops) = cfg.toEntry()
            rollService.registerTable(tableId, drops)
        }
    }
}
