package com.astarworks.astera.combat.application.contents

import com.astarworks.astera.combat.application.config.ArmorYamlConfig
import com.astarworks.astera.combat.application.port.outbound.IArmorRegistry
import com.astarworks.astera.combat.domain.armor.ArmorId
import com.astarworks.astera.combat.domain.armor.ArmorSet
import com.astarworks.astera.combat.domain.armor.ArmorSetKey
import com.astarworks.astera.combat.domain.armor.ArmorSpec
import com.astarworks.astera.core.application.contents.ReloadableContents
import com.astarworks.astera.core.application.port.outbound.IContentParser
import com.astarworks.astera.core.application.service.ContentLoader
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * Armor-side dual-storage `Contents` (Wave _infra-2b, ADR-0065).
 *
 * Replaces the legacy `MutableArmorRegistry` + `ArmorLoaderService` pair. The
 * `content/armor/` directory holds a polymorphic mix of pieces ([ArmorSpec])
 * and named sets ([ArmorSet]) under the same DTO envelope
 * ([ArmorYamlConfig]) — see the `type:` discriminator on the sealed config.
 * One YAML file produces exactly one entry on exactly one side.
 *
 * # Why this doesn't extend [com.astarworks.astera.core.application.contents.YamlDynamicContents]
 *
 * The substrate is single-store (`Map<K, V>`); armor needs two stores keyed
 * by different identifiers. We hand-implement [ReloadableContents] + the
 * `Contents` surface required by [IArmorRegistry] so the same atomic-swap
 * invariant holds **across both maps simultaneously**: a single bad file in
 * the batch aborts both swaps.
 *
 * # Atomicity
 *
 * Each map is an [AtomicReference] read by every `find` / `all` call. A
 * successful [reload] swaps both maps in two `set` calls — they are NOT a
 * single atomic transaction across the pair, but the window between them is
 * a few nanoseconds and no consumer reads both stores in lockstep (pieces and
 * sets are looked up independently, then composed at hit-resolution time).
 * Phase 4 may revisit if multi-map atomicity becomes load-bearing.
 */
public class ArmorContents(
    parsers: List<IContentParser> = ContentLoader.DEFAULT_PARSERS,
) : IArmorRegistry, ReloadableContents {

    override val categoryName: String = "armor"

    private val piecesStore: AtomicReference<Map<ArmorId, ArmorSpec>> = AtomicReference(emptyMap())
    private val setsStore: AtomicReference<Map<ArmorSetKey, ArmorSet>> = AtomicReference(emptyMap())

    private val loader = ContentLoader(
        parsers = parsers,
        deserializer = ArmorYamlConfig.serializer(),
    )

    // ----- Contents<ArmorId, ArmorSpec> (pieces side) ------------------------

    override fun find(key: ArmorId): ArmorSpec? = piecesStore.get()[key]

    override fun all(): Collection<ArmorSpec> = piecesStore.get().values

    // ----- IArmorRegistry sets side -----------------------------------------

    override fun findSet(key: ArmorSetKey): ArmorSet? = setsStore.get()[key]

    override fun allSets(): Collection<ArmorSet> = setsStore.get().values

    // ----- Programmatic registration ----------------------------------------

    /**
     * Manually add one armor piece. Atomic — concurrent readers see either
     * the old map or the old+1 map, never a torn state.
     */
    public fun register(id: ArmorId, spec: ArmorSpec) {
        while (true) {
            val current = piecesStore.get()
            val next = current + (id to spec)
            if (piecesStore.compareAndSet(current, next)) return
        }
    }

    /** Convenience overload: register by spec alone (the spec owns its id). */
    public fun register(spec: ArmorSpec) {
        register(spec.id, spec)
    }

    /**
     * Manually add one armor set definition. Atomic — concurrent readers see
     * either the old map or the old+1 map, never a torn state.
     */
    public fun registerSet(key: ArmorSetKey, set: ArmorSet) {
        while (true) {
            val current = setsStore.get()
            val next = current + (key to set)
            if (setsStore.compareAndSet(current, next)) return
        }
    }

    /** Convenience overload: register by set alone (the set owns its key). */
    public fun registerSet(set: ArmorSet) {
        registerSet(set.key, set)
    }

    /** Drop every registered piece and set. Used by hot-reload semantics tests. */
    public fun clear() {
        piecesStore.set(emptyMap())
        setsStore.set(emptyMap())
    }

    // ----- Bootstrap + reload -----------------------------------------------

    /**
     * Best-effort initial load at `onEnable`. Files that parse register;
     * files that fail are logged by `ContentLoader` and counted in
     * [ContentLoader.LoadReport.failed]. Existing in-memory state is
     * preserved across calls (e.g., if [register] was called before
     * [bootstrap], those entries survive the load).
     */
    public fun bootstrap(dir: Path): ContentLoader.LoadReport {
        val incomingPieces = LinkedHashMap<ArmorId, ArmorSpec>()
        val incomingSets = LinkedHashMap<ArmorSetKey, ArmorSet>()
        val report = loader.loadFrom(dir) { entry ->
            when (entry) {
                is ArmorYamlConfig.ArmorPieceEntry -> {
                    val spec = entry.toSpec()
                    incomingPieces[spec.id] = spec
                }
                is ArmorYamlConfig.ArmorSetEntry -> {
                    val set = entry.toSet()
                    incomingSets[set.key] = set
                }
            }
        }
        while (true) {
            val current = piecesStore.get()
            val next = current + incomingPieces
            if (piecesStore.compareAndSet(current, next)) break
        }
        while (true) {
            val current = setsStore.get()
            val next = current + incomingSets
            if (setsStore.compareAndSet(current, next)) break
        }
        return report
    }

    /**
     * Atomic, all-or-nothing reload across both pieces and sets. On
     * `failed > 0` the previous state of BOTH maps remains untouched and the
     * report is returned so the operator can fix the offending YAMLs and
     * retry. On `failed == 0` both maps are replaced.
     *
     * Atomicity caveat: see the class KDoc — the two `set` calls are not a
     * single CPU atomic, but the window between them is negligible and no
     * call site reads piece + set in lockstep.
     */
    override fun reload(dir: Path): ContentLoader.LoadReport {
        val nextPieces = LinkedHashMap<ArmorId, ArmorSpec>()
        val nextSets = LinkedHashMap<ArmorSetKey, ArmorSet>()
        val report = loader.loadFrom(dir) { entry ->
            when (entry) {
                is ArmorYamlConfig.ArmorPieceEntry -> {
                    val spec = entry.toSpec()
                    nextPieces[spec.id] = spec
                }
                is ArmorYamlConfig.ArmorSetEntry -> {
                    val set = entry.toSet()
                    nextSets[set.key] = set
                }
            }
        }
        if (report.failed == 0) {
            piecesStore.set(nextPieces.toMap())
            setsStore.set(nextSets.toMap())
        }
        return report
    }
}
