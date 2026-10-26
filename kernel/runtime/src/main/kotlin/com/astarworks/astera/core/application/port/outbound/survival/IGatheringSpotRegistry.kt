package com.astarworks.astera.core.application.port.outbound.survival

import com.astarworks.astera.core.application.contents.Contents
import com.astarworks.astera.core.domain.survival.GatheringSpot
import com.astarworks.astera.core.domain.survival.GatheringSpotId

/**
 * Read-side registry of [GatheringSpot]s keyed by [GatheringSpotId] (Wave
 * 5pve-survival / ADR-0107).
 *
 * Mirrors the [com.astarworks.astera.core.application.port.outbound.mob.IMobRegistry]
 * pattern — concrete impl `GatheringSpotContents` extends
 * `YamlDynamicContents` so `/astera-survival reload gathering` slots into
 * the shared `IReloadableContentsRegistry`.
 *
 * Read-only contract: mutation lives on the concrete `GatheringSpotContents`
 * (bootstrap / reload / register / clear). Use cases depend on this
 * interface so they cannot accidentally mutate the registry through the
 * port.
 */
public interface IGatheringSpotRegistry : Contents<GatheringSpotId, GatheringSpot>
