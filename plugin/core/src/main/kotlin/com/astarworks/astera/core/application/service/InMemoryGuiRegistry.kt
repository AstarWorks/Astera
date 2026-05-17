package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.IGuiRegistry
import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.gui.GuiSpec
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory implementation of [IGuiRegistry] used by Wave 4 R3 (ADR-0021).
 *
 * Mirrors the shape of [MutableItemRegistry] / `MutableWeaponRegistry`: a plain
 * [ConcurrentHashMap] keyed by [GuiId], populated at startup by
 * [GuiLoaderService] from `content/gui/` (yaml/toml/json) and optionally by
 * use cases that compose specs at runtime.
 *
 * # Thread safety
 *
 * Backing store is concurrent so the (rare) case of a use case registering a
 * dynamically-composed spec while a GUI click reads is safe. Lookups happen on
 * the Paper main thread for GUI opens, but feature plugins may register from
 * any thread per ADR-0022 wiring guidance, so we don't downgrade to a plain
 * `HashMap`.
 *
 * # Replacement semantics
 *
 * `register` is last-write-wins per the [IGuiRegistry.register] contract — the
 * loader's Phase 4 hot-reload story relies on this.
 */
public class InMemoryGuiRegistry : IGuiRegistry {

    private val specs: ConcurrentHashMap<GuiId, GuiSpec> = ConcurrentHashMap()

    override fun register(spec: GuiSpec) {
        specs[spec.id] = spec
    }

    override fun resolve(id: GuiId): GuiSpec? = specs[id]

    override fun all(): Collection<GuiSpec> = specs.values.toList()

    /** Drop every registered entry. Reserved for Phase 4 hot-reload. */
    public fun clear() {
        specs.clear()
    }
}
