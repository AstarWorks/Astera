package com.astarworks.astera.core.domain.stage

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * A player- (or guild-) owned region inside a persistent world. Foundation
 * for Phase 5 "Metaverse Place" — the actual permission / build-rights logic
 * lives there.
 *
 * `owner == null` means an unclaimed plot (e.g. preview / lobby plot).
 */
public data class Plot(
    val id: PlotId,
    val owner: PlayerId?,
    val region: Region,
)
