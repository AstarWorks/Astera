package com.astarworks.astera.stats.application.service

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.XpSource
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.port.outbound.ILevelService
import com.astarworks.astera.stats.domain.level.Level
import com.astarworks.astera.stats.domain.level.Xp
import com.astarworks.astera.stats.domain.level.XpCurve
import java.util.concurrent.ConcurrentHashMap

public class InMemoryLevelService : ILevelService {

    /** Total accumulated XP per player. */
    private val xpStore: ConcurrentHashMap<PlayerId, Long> = ConcurrentHashMap()

    override fun currentLevel(playerId: PlayerId): Level {
        val total = xpStore[playerId] ?: 0L
        return XpCurve.levelFor(total)
    }

    override fun totalXp(playerId: PlayerId): Xp = Xp(xpStore[playerId] ?: 0L)

    override fun grantXp(playerId: PlayerId, amount: Xp, source: XpSource): Result<ILevelService.LevelChange, Nothing> {
        val oldTotal = xpStore[playerId] ?: 0L
        val newTotal = oldTotal + amount.value
        xpStore[playerId] = newTotal

        val oldLevel = XpCurve.levelFor(oldTotal)
        val newLevel = XpCurve.levelFor(newTotal)

        return Result.success(
            ILevelService.LevelChange(
                oldLevel = oldLevel,
                newLevel = newLevel,
                totalXp = Xp(newTotal),
                leveledUp = newLevel.value > oldLevel.value,
            ),
        )
    }
}
