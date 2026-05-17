package com.astarworks.astera.core.domain.stage

import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * A named stage (map) used by matches or persistent places.
 *
 * Stage geometry (WorldEdit schematic, etc.) is held elsewhere — this type
 * carries only the metadata Astera needs to reason about which stage is
 * which.
 *
 * Wave 4.5 (R3.5, ADR-0027) added [spawnPoints]: a small list of
 * role-tagged positions used by `MatchSpawnUseCase`. Spawn YAML keys map
 * 1:1 to the [SpawnPoint] sealed variants.
 */
public data class Stage(
    val id: StageId,
    val displayNameKey: MessageKey,
    val regions: List<Region>,
    val spawnPoints: List<SpawnPoint> = emptyList(),
)
