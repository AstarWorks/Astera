package com.astarworks.astera.combat.application.contents

import com.astarworks.astera.combat.application.config.ComboRouteYamlConfig
import com.astarworks.astera.combat.domain.combo.ComboRoute
import com.astarworks.astera.combat.domain.combo.ComboRouteId
import com.astarworks.astera.core.application.contents.YamlDynamicContents

/**
 * Combo-route-side `Contents<ComboRouteId, ComboRoute>` (Wave 5combat-depth,
 * ADR-0105).
 *
 * Loads YAML files under `content/combat-depth/combos/`, atomic-swap storage,
 * participates in cross-plugin reload registry. Mirrors `SkillContents`
 * exactly — combo routes are pure value content (no cross-field
 * registry-level invariants to check at load time; cross-route invariants
 * like "no duplicate weapon × pattern" can be added later).
 *
 * Wiring (Phase C):
 *  - `AsteraCombatPlugin` instantiates one [ComboRouteContents] at boot.
 *  - On boot, every loaded [ComboRoute] is forwarded into the
 *    `IComboTracker` via `registerRoute(...)`.
 *  - On `/astera reload`, the reload registry calls [reload] here, then
 *    re-syncs the tracker (clear + re-register).
 */
public class ComboRouteContents : YamlDynamicContents<ComboRouteId, ComboRoute, ComboRouteYamlConfig>(
    categoryName = "combat-depth/combos",
    deserializer = ComboRouteYamlConfig.serializer(),
) {

    /**
     * Convert each YAML DTO to its `(ComboRouteId, ComboRoute)` entry. Delegates
     * to [ComboRouteYamlConfig.toEntry] so any future schema evolution stays
     * single-sourced in the DTO.
     */
    override fun toEntry(config: ComboRouteYamlConfig): Pair<ComboRouteId, ComboRoute> =
        config.toEntry()

    /**
     * Convenience overload: register by route alone (the route owns its id).
     * Used by test harnesses and admin commands that build a [ComboRoute]
     * directly in memory.
     */
    public fun register(route: ComboRoute) {
        register(route.id, route)
    }
}
