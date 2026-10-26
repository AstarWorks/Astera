package com.astarworks.astera.core.application.service.survival

import com.astarworks.astera.core.application.port.outbound.survival.IIslandTemplateRegistry
import com.astarworks.astera.core.domain.survival.IslandTemplate
import com.astarworks.astera.core.domain.survival.IslandTemplateId
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [IIslandTemplateRegistry] populated by the platform bootstrap
 * (Wave 5pve-survival / ADR-0107).
 *
 * Phase 4 replaces this with a `YamlDynamicContents`-based variant once
 * the template YAML schema settles.
 */
public class InMemoryIslandTemplateRegistry : IIslandTemplateRegistry {

    private val templates: ConcurrentHashMap<IslandTemplateId, IslandTemplate> = ConcurrentHashMap()

    override fun find(id: IslandTemplateId): IslandTemplate? = templates[id]

    override fun all(): Collection<IslandTemplate> = templates.values.toList()

    public fun register(template: IslandTemplate) {
        templates[template.id] = template
    }

    public fun clear() {
        templates.clear()
    }
}
