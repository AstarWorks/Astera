package com.astarworks.astera.stats.application.eventbus.listeners

import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.domain.event.XpSource
import com.astarworks.astera.stats.application.usecase.level.GrantXpUseCase
import com.astarworks.astera.stats.application.usecase.stats.RecordKillUseCase

public class RewardOnKillListener(
    private val recordKill: RecordKillUseCase,
    private val grantXp: GrantXpUseCase,
) {
    public fun handle(event: PlayerKilled) {
        val killerId = event.killer ?: return
        recordKill.execute(killerId)
        grantXp.execute(killerId, 100L, XpSource.Kill)
    }
}
