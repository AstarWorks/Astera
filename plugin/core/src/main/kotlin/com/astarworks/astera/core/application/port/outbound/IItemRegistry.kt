package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.application.contents.Contents
import com.astarworks.astera.core.domain.item.ItemId

/**
 * Outbound port: read-only lookup of registered item templates by [ItemId].
 *
 * **The single window onto every item Astera knows about.** Per Stage 1 D
 * (Phase 2 mid), every weapon / GUI slot / loadout / shop entry resolves
 * through this registry — `ItemId` is the primary key for item references.
 *
 * Generic over [T] so the application layer stays vendor-neutral. Production
 * code wires `IItemRegistry<com.astarworks.astera.core.adapter.api.item.IMcItemTemplate>`,
 * tests can wire `IItemRegistry<TestItem>`; either way the application's
 * use cases only see `T?` from [Contents.find] and never assume a renderer.
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
public interface IItemRegistry<T : Any> : Contents<ItemId, T>
