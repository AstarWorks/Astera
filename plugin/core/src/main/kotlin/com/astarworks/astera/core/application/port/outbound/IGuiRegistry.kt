package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.gui.GuiSpec

/**
 * Outbound port: look up declared [GuiSpec]s by [GuiId].
 *
 * Mirrors [IItemRegistry] / [IWeaponRegistry] in shape: the application
 * layer asks "what does GUI X look like?" and gets back an immutable spec.
 * Populated at plugin startup by `GuiLoaderService` from `content/gui/`
 * (and friends per ADR-0016) the same way items and weapons are loaded.
 *
 * Lives in `plugin/core` so the registry's `KClass` identity is consistent
 * across plugins (per ADR-0020 §"Cross-plugin KClass identity") — any plugin
 * can `services.getOrThrow<IGuiRegistry>()` and resolve specs declared by
 * core's content loader (or registered programmatically by a use case at
 * runtime).
 */
public interface IGuiRegistry {
    /** Look up [id]; returns the registered spec or `null` if unknown. */
    public fun resolve(id: GuiId): GuiSpec?

    /**
     * Register [spec]. If a spec with the same [GuiSpec.id] already exists,
     * it is replaced (last-write-wins). The loader uses this at startup;
     * use cases can use it at runtime to register dynamically composed
     * specs.
     */
    public fun register(spec: GuiSpec)

    /** Snapshot of every currently-registered spec. */
    public fun all(): Collection<GuiSpec>
}
