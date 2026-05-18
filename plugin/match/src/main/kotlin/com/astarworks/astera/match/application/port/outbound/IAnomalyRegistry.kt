package com.astarworks.astera.match.application.port.outbound

import com.astarworks.astera.core.application.contents.Contents
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.domain.anomaly.AnomalyDescriptor

/**
 * Outbound port: read-only lookup of authored anomaly schedules
 * (Wave _infra-2b, ADR-0065).
 *
 * Keyed by [StageId] rather than `AnomalyId` because the on-disk YAML unit
 * is a per-stage schedule (`AnomalyScheduleYamlConfig`) containing a list
 * of descriptors. `AnomalyId` identifies a *runtime instance* triggered by
 * the scheduler, not an authored content row — see
 * [com.astarworks.astera.match.domain.anomaly.AnomalyId] kdoc. The
 * [IAnomalyScheduleRepository] (separate concern) holds per-match runtime
 * state; this registry holds the immutable authored definitions.
 *
 * Populated at plugin startup by `AnomalyContents.bootstrap` and
 * hot-reloaded by `/astera reload anomaly` (or the per-plugin equivalent).
 *
 * Extends [Contents] so the substrate's `find` / `all` contract is shared
 * with every other content type (weapons, stages, items, …).
 *
 * TODO(infra-2b orchestrator): if Phase C decides AnomalyId-keyed lookup
 * is needed for command UX (e.g., `/astera trigger-anomaly <id>`), revisit
 * the key shape. For Wave _infra-2b the scheduler consumes the list per
 * StageId so the current shape suffices.
 */
public interface IAnomalyRegistry : Contents<StageId, List<AnomalyDescriptor>>
