package com.astarworks.astera.stats.domain.norma

import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

public data class MissionProgress(
    val playerId: PlayerId,
    val normaId: NormaId,
    val current: Long,
    val completed: Boolean,
    val completedAt: Instant?,
)
