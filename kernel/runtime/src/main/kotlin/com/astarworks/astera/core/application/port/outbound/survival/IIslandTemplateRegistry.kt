package com.astarworks.astera.core.application.port.outbound.survival

import com.astarworks.astera.core.domain.survival.IslandTemplate
import com.astarworks.astera.core.domain.survival.IslandTemplateId

/**
 * Read-side registry of [IslandTemplate]s (Wave 5pve-survival / ADR-0107).
 *
 * Foundation wave keeps this minimal: in-memory registry populated by
 * `CreatePartyIslandUseCase` callers (typically the platform plugin
 * bootstrap or a test). Phase 4 promotes to YAML-loaded
 * `content/island-templates/<id>.yaml` and joins the
 * `IReloadableContentsRegistry` dispatcher.
 *
 * Why not `Contents<IslandTemplateId, IslandTemplate>` today: the YAML
 * shape for templates is undecided (`floating-plains` / `tier-1-mountain`
 * variants want different schema keys). Foundation pins the interface
 * surface so the use cases can resolve a template without coupling to
 * YAML — Phase 4 grafts `YamlDynamicContents` on without touching the
 * port.
 */
public interface IIslandTemplateRegistry {
    public fun find(id: IslandTemplateId): IslandTemplate?
    public fun all(): Collection<IslandTemplate>
}
