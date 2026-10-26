package com.astarworks.astera.core.domain.norma

import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

public data class MissionProgress(
    val playerId: PlayerId,
    val normaId: NormaId,
    val current: Long,
    val completed: Boolean,
    val completedAt: Instant?,
    /** The daily / weekly window this row counts for (`2026-10-22`, `2026-W43`); `""` = does not reset. */
    val periodKey: String = "",
)
