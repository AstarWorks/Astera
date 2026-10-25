package com.astarworks.astera.core.application.contents

import com.astarworks.astera.core.application.config.GuiYamlConfig
import com.astarworks.astera.core.application.port.outbound.IGuiRegistry
import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.gui.GuiSpec

/**
 * GUI-side `Contents<GuiId, GuiSpec>` (Wave _infra-2b, ADR-0065).
 *
 * Replaces the legacy `InMemoryGuiRegistry` + `GuiLoaderService` pair.
 * Inherits from [YamlDynamicContents]:
 *
 *   - `find(id) / all()` from [Contents] (the `IGuiRegistry` port surface)
 *   - `bootstrap(dir)` for `onEnable` initial load (best-effort)
 *   - `reload(dir)` for `/astera reload gui` (all-or-nothing, atomic swap)
 *   - `register(id, spec) / clear()` for tests + programmatic registration
 *
 * Adds a [register] overload that accepts a [GuiSpec] alone (the spec owns
 * its id). This mirrors the legacy `IGuiRegistry.register(spec)` shape used
 * by use cases that compose specs at runtime (e.g. the loadout toggle's
 * `withSlot` patch), so existing call sites can move from
 * `IGuiRegistry.register(spec)` to `GuiContents.register(spec)` without
 * changing their argument list.
 *
 * No state of its own — the substrate's `AtomicReference<Map<GuiId, GuiSpec>>`
 * is the single source of truth and is read by every `IGuiRegistry` consumer
 * through `find()` (so concurrent readers always see a self-consistent
 * snapshot, never a torn map).
 */
public class GuiContents : YamlDynamicContents<GuiId, GuiSpec, GuiYamlConfig>(
    categoryName = "gui",
    deserializer = GuiYamlConfig.serializer(),
), IGuiRegistry {

    /**
     * Convert each YAML DTO to its `(GuiId, GuiSpec)` entry. Delegates to
     * [GuiYamlConfig.toSpec] so any future schema evolution stays
     * single-sourced in the DTO.
     */
    override fun toEntry(config: GuiYamlConfig): Pair<GuiId, GuiSpec> {
        val spec = config.toSpec()
        return spec.id to spec
    }

    /**
     * Convenience overload: register by spec alone (the spec owns its id).
     * Mirrors the legacy `InMemoryGuiRegistry.register(spec)` shape so use
     * cases that build a [GuiSpec] in memory (e.g. loadout toggle's
     * `withSlot` patch) keep their call signature.
     */
    public fun register(spec: GuiSpec) {
        register(spec.id, spec)
    }
}
