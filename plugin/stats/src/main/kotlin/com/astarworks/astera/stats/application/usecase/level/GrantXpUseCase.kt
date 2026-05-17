package com.astarworks.astera.stats.application.usecase.level

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.XpSource
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.port.outbound.ILevelService
import com.astarworks.astera.stats.domain.event.LeveledUp
import com.astarworks.astera.stats.domain.event.XpGranted

public class GrantXpUseCase(
    private val levelService: ILevelService,
    private val eventBus: IDomainEventBus,
) {
    public fun execute(
        playerId: PlayerId,
        amount: Long,
        source: XpSource,
    ): Result<ILevelService.LevelChange, GrantXpError> {
        if (amount <= 0) return Result.failure(GrantXpError.InvalidAmount(amount))

        val change = levelService.grantXp(playerId, com.astarworks.astera.stats.domain.level.Xp(amount), source).getOrNull()!!

        eventBus.publish(
            XpGranted(
                playerId = playerId,
                amount = amount,
                newTotal = change.totalXp.value,
                newLevel = if (change.leveledUp) change.newLevel.value else null,
                source = source,
            ),
        )

        if (change.leveledUp) {
            eventBus.publish(
                LeveledUp(
                    playerId = playerId,
                    oldLevel = change.oldLevel,
                    newLevel = change.newLevel,
                    totalXp = change.totalXp,
                ),
            )
        }

        return Result.success(change)
    }

    public companion object {
        public val K_INVALID_AMOUNT: MessageKey = MessageKey("astera.stats.xp.invalid-amount")
    }
}

public sealed class GrantXpError {
    public data class InvalidAmount(val amount: Long) : GrantXpError()
}
