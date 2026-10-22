package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.loginbonus.LoginBonusReward
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/** A player collected today's login bonus (ADR-0210). Published after the reward was credited and the streak saved. */
public data class LoginBonusClaimed(
    public val playerId: PlayerId,
    public val streakDays: Int,
    public val day: Int,
    public val reward: LoginBonusReward,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
