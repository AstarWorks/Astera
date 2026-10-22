package com.astarworks.astera.stats.application.service

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.XpSource
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.port.outbound.ILevelService
import com.astarworks.astera.stats.domain.level.Level
import com.astarworks.astera.stats.domain.level.Xp
import com.astarworks.astera.stats.domain.level.XpCurve
import com.astarworks.astera.stats.application.port.outbound.IPlayerXpRepository

public class InMemoryLevelService(
    /** Total accumulated XP per player; Persistence overlays the default (ADR-0187) so XP survives restarts. */
    private val xpStore: IPlayerXpRepository = InMemoryPlayerXpRepository(),
) : ILevelService {

    override fun currentLevel(playerId: PlayerId): Level = XpCurve.levelFor(xpStore.totalXp(playerId))

    override fun totalXp(playerId: PlayerId): Xp = Xp(xpStore.totalXp(playerId))

    override fun grantXp(playerId: PlayerId, amount: Xp, source: XpSource): Result<ILevelService.LevelChange, Nothing> {
        val newTotal = xpStore.addXp(playerId, amount.value)
        val oldTotal = newTotal - amount.value

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
