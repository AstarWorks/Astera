package com.astarworks.astera.stats.application.port.outbound

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.XpSource
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.domain.level.Level
import com.astarworks.astera.stats.domain.level.Xp

public interface ILevelService {
    public fun currentLevel(playerId: PlayerId): Level
    public fun totalXp(playerId: PlayerId): Xp
    public fun grantXp(playerId: PlayerId, amount: Xp, source: XpSource): Result<LevelChange, Nothing>

    public data class LevelChange(
        val oldLevel: Level,
        val newLevel: Level,
        val totalXp: Xp,
        val leveledUp: Boolean,
    )
}
