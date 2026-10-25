package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.application.contents.Contents
import com.astarworks.astera.core.domain.advancement.AdvancementId
import com.astarworks.astera.core.domain.advancement.CustomAdvancement

/**
 * Read-only catalog of [CustomAdvancement] templates (Wave 5p, ADR-0088).
 *
 * Populated at startup by `AdvancementContents.bootstrap` and hot-reloadable
 * via `/astera reload advancements`. Mirrors [IItemRegistry] / [ITutorialRegistry]
 * — the marker extends [Contents] so the underlying substrate (YamlDynamicContents)
 * provides `find` / `all` / `bootstrap` / `reload` uniformly.
 *
 * Phase 4 (UGC marketplace) may swap to a DB-backed `Contents<AdvancementId,
 * CustomAdvancement>` implementation without touching any use case.
 */
public interface IAdvancementRegistry : Contents<AdvancementId, CustomAdvancement>
