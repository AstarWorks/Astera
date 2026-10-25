package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.application.contents.Contents
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.ItemSpec

/**
 * Outbound port: read-only lookup of registered item specs by [ItemId].
 *
 * **The single window onto every item Astera knows about.** Per Stage 1 D
 * (Phase 2 mid) + ADR-0073, every weapon / GUI slot / loadout / shop entry
 * resolves through this registry — `ItemId` is the primary key for item
 * references, [ItemSpec] is the canonical materialized form.
 *
 * # Wave _items-1 (ADR-0073) change
 *
 * Pre-_items-1 this port was generic — `IItemRegistry<T : Any>` — so callers
 * had to choose between `IMcItemTemplate` (Paper) and a test-only stand-in.
 * After _items-1, the YAML loader produces an [ItemSpec] directly, and every
 * caller works on `ItemSpec`. Adapter-side concerns (Material lookup,
 * `CustomModelData`, NBT) happen at give-time in `:plugin:items`'s adapter
 * layer.
 *
 * Populated at plugin startup by
 * [com.astarworks.astera.core.application.contents.ItemContents] via the
 * substrate's `bootstrap(dir)` / `reload(dir)` (ADR-0065); Phase 4 (UGC
 * marketplace) may swap to a DB-backed implementation without touching
 * application code.
 *
 * Extends [Contents] so the substrate's `find` / `all` contract is shared
 * with every other content type (weapons, stages, skills, …). The marker
 * adds no methods of its own.
 */
public interface IItemRegistry : Contents<ItemId, ItemSpec>
