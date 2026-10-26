package com.astarworks.astera.items.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.items.domain.refinement.ItemInstance
import com.astarworks.astera.items.domain.refinement.ItemInstanceId

/**
 * Outbound port: per-player [ItemInstance] persistence (Wave 5refine,
 * ADR-0100 §Persistence).
 *
 * Wave 5refine ships the in-memory default in `:plugin:items` at priority 0;
 * `:plugin:persistence` registers a Postgres-backed impl at priority 100 via
 * V30 (`item_instances`). The pattern mirrors every other cross-plugin
 * repository (Wave 3 / ADR-0020 + Wave 5f / ADR-0035 marketplace).
 *
 * # Storage shape
 *
 *   - One row per instance keyed by [ItemInstanceId.value] (UUID).
 *   - `layers` JSONB carries the [com.astarworks.astera.items.domain.refinement.Refinement]
 *     sealed-variant snapshot.
 *   - `sub_stat_rolls` JSONB carries the
 *     [com.astarworks.astera.items.domain.refinement.SubStatRoll] list.
 *   - `reroll_count` is materialised as a queryable column so analytics +
 *     leaderboards do not need to crack open JSONB.
 *
 * # Why this lives in `items.application`, not Core
 *
 * Per ADR-0073 sibling-isolation: the [ItemInstance] type is owned by Items.
 * Persistence depends on Items only as a softdepend (its purpose is to
 * overlay defaults), so this port is `:plugin:items` -owned and the Postgres
 * impl is the only Persistence-side dependency on Items domain types.
 */
public interface IItemInstanceRepository {

    /** Lookup by id; `null` if the instance does not exist. */
    public suspend fun findById(id: ItemInstanceId): ItemInstance?

    /** Every instance owned by [player]. Order is implementation-defined. */
    public suspend fun findByOwner(player: PlayerId): List<ItemInstance>

    /**
     * Insert or overwrite the instance keyed by [ItemInstance.id]. The
     * container is *derived state*; impls serialise only the layers /
     * sub-stat rolls / reroll count + immutable header.
     */
    public suspend fun save(instance: ItemInstance)

    /** Hard-delete; Phase 1 only used by tests + admin tools. */
    public suspend fun delete(id: ItemInstanceId)
}
