package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.game.InstanceId

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.result.MatchRewardResult
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/** Every participant's payout for [matchId], published once after the wallets were credited. */
public data class MatchRewardsGranted(
    val matchId: MatchId,
    val rewards: Map<PlayerId, MatchRewardResult>,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Instance(InstanceId(matchId.value))
}
