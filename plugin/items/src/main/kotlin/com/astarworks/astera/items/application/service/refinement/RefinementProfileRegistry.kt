package com.astarworks.astera.items.application.service.refinement

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.items.application.config.refinement.RefinementProfileYaml
import com.astarworks.astera.items.domain.refinement.RefinementSpec
import java.util.concurrent.ConcurrentHashMap

/**
 * In-process registry of per-item refinement profiles (Wave 5refine,
 * ADR-0100).
 *
 * Loaded at plugin startup from `content/refinement/<id>.yaml`; exposes a
 * single `(ItemId) -> RefinementSpec` resolver consumed by both use cases
 * (`ApplyRefinementUseCase`, `RerollSubStatsUseCase`).
 *
 * Returns [RefinementSpec.DISABLED] for any item that does not have a
 * matching profile — so the use cases can short-circuit with
 * `RefinementError.NotRefinable` rather than NPE.
 *
 * Thread-safety: the underlying map is concurrent so background YAML reload
 * and main-thread use-case lookups do not race.
 */
public class RefinementProfileRegistry {

    private val profiles: MutableMap<ItemId, RefinementSpec> = ConcurrentHashMap()

    public fun register(itemId: ItemId, spec: RefinementSpec) {
        profiles[itemId] = spec
    }

    public fun registerFromYaml(yaml: RefinementProfileYaml) {
        val id = ItemId(yaml.id)
        profiles[id] = yaml.profile.toDomain()
    }

    /** Resolver compatible with `(ItemId) -> RefinementSpec` for use-case injection. */
    public fun resolve(itemId: ItemId): RefinementSpec =
        profiles[itemId] ?: RefinementSpec.DISABLED

    public fun all(): Map<ItemId, RefinementSpec> = profiles.toMap()

    public fun clear() {
        profiles.clear()
    }
}
