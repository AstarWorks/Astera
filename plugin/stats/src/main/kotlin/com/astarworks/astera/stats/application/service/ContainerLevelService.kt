package com.astarworks.astera.stats.application.service

import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.attribute.AttributeKey
import com.astarworks.astera.core.domain.attribute.AttributeValue
import com.astarworks.astera.core.domain.event.XpSource
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.port.outbound.ILevelService
import com.astarworks.astera.stats.domain.level.Level
import com.astarworks.astera.stats.domain.level.Xp
import com.astarworks.astera.stats.domain.level.XpCurve

/**
 * [ILevelService] over the player-attribute container (`progress.xp`), so
 * experience earned on a game server is the same experience the lobby shows:
 * the container is the row that is shared through Postgres. [store] is resolved
 * per call because the Postgres overlay may register after this plugin.
 */
public class ContainerLevelService(
    private val store: () -> IPlayerAttributeContainerStore,
) : ILevelService {

    override fun currentLevel(playerId: PlayerId): Level = XpCurve.levelFor(totalOf(playerId))

    override fun totalXp(playerId: PlayerId): Xp = Xp(totalOf(playerId))

    override fun grantXp(playerId: PlayerId, amount: Xp, source: XpSource): Result<ILevelService.LevelChange, Nothing> {
        val oldTotal = totalOf(playerId)
        val newTotal = oldTotal + amount.value
        store().containerFor(playerId).set(XP, AttributeValue.NumberValue(newTotal.toDouble()))
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

    private fun totalOf(playerId: PlayerId): Long =
        (store().containerFor(playerId).get(XP) as? AttributeValue.NumberValue)?.value?.toLong()?.coerceAtLeast(0L) ?: 0L

    private companion object {
        val XP = AttributeKey("progress.xp")
    }
}
