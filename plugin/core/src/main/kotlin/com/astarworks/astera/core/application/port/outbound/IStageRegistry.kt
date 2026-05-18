package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.application.contents.Contents
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.core.domain.stage.StageId

/**
 * Outbound port: read-only lookup of registered stages (Wave _infra-2b).
 *
 * The registry is populated at plugin startup by `StageContents.bootstrap`
 * and hot-reloaded by `/astera reload stages` (or the per-plugin equivalent).
 *
 * Extends [Contents] so the substrate's `find` / `all` contract is shared
 * with every other content type (weapons, items, skills, …). Adds no methods
 * of its own — the marker interface keeps call sites typed at
 * `IStageRegistry` while the impl participates in the reload dispatcher.
 *
 * Supersedes the legacy `IStageRepository : Repository<StageId, Stage>` —
 * stages are immutable content authored in `content/stages/`, not mutable
 * runtime state, so the CRUD surface (save / delete) is dropped.
 */
public interface IStageRegistry : Contents<StageId, Stage>
