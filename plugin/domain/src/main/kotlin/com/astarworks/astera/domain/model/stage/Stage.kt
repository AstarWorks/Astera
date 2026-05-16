package com.astarworks.astera.domain.model.stage

import com.astarworks.astera.domain.model.i18n.MessageKey

/**
 * A named stage (map) used by matches or persistent places.
 *
 * Stage geometry (WorldEdit schematic, etc.) is held elsewhere — this type
 * carries only the metadata Astera needs to reason about which stage is
 * which.
 */
public data class Stage(
    val id: StageId,
    val displayNameKey: MessageKey,
    val regions: List<Region>,
)
