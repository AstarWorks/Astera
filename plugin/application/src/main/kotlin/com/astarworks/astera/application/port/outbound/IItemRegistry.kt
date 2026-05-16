package com.astarworks.astera.application.port.outbound

import com.astarworks.astera.domain.model.item.ItemId

/**
 * Outbound port: read-only lookup of registered item templates by [ItemId].
 *
 * **The single window onto every item Astera knows about.** Per Stage 1 D
 * (Phase 2 mid), every weapon / GUI slot / loadout / shop entry resolves
 * through this registry — `ItemId` is the primary key for item references.
 *
 * Generic over [T] so the application layer stays vendor-neutral. Production
 * code wires `IItemRegistry<com.astarworks.astera.adapter.minecraftapi.item.IMcItemTemplate>`,
 * tests can wire `IItemRegistry<TestItem>`; either way the application's
 * use cases only see `T?` and never assume a renderer.
 *
 * Populated at plugin startup by [com.astarworks.astera.application.service.MutableItemRegistry]
 * via [com.astarworks.astera.application.service.ContentLoader]; Phase 4
 * (UGC marketplace) may swap to a DB-backed implementation without touching
 * application code.
 */
public interface IItemRegistry<T : Any> {
    /** Returns the registered template for [id], or `null` if unknown. */
    public fun resolveOrNull(id: ItemId): T?

    /** Snapshot of every currently-registered template. */
    public fun list(): Collection<T>
}
