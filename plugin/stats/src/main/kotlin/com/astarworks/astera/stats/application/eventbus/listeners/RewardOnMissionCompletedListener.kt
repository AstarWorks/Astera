package com.astarworks.astera.stats.application.eventbus.listeners

import com.astarworks.astera.core.application.port.outbound.IRewardBridge
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.reward.RewardBundle
import com.astarworks.astera.core.domain.reward.RewardBundleId
import com.astarworks.astera.stats.domain.event.MissionCompleted
import java.time.Clock

/**
 * On [MissionCompleted], enqueues a cross-server [RewardBundle] derived
 * from the norma's [com.astarworks.astera.core.domain.norma.NormaReward].
 *
 * The reward carries `currencyId: String?` (Stats may not import economy.*
 * per ADR-0022). This listener parses the id into [Currency] case-insensitively;
 * unknown / null ids fall back to the Phase 1 PoC default (25 STAR + 10 XP).
 *
 * silly-knuth §5: in-process only — the lobby side consumes via
 * [IRewardBridge.consumeForPlayer] when the player reconnects.
 */
public class RewardOnMissionCompletedListener(
    private val bridge: IRewardBridge,
    private val clock: Clock = Clock.systemUTC(),
) {

    public fun handle(event: MissionCompleted) {
        val reward = event.reward
        val currencyId = reward.currencyId
        val parsed: Currency? = currencyId
            ?.takeIf { it.isNotBlank() }
            ?.let { id -> Currency.entries.firstOrNull { it.name.equals(id, ignoreCase = true) } }

        val (currency, amount) = if (parsed != null && reward.currencyAmount > 0L) {
            parsed to reward.currencyAmount
        } else {
            Currency.STAR to DEFAULT_STAR
        }
        val xp = if (reward.xp > 0L) reward.xp else DEFAULT_XP

        val bundle = RewardBundle(
            id = RewardBundleId.random(),
            playerId = event.playerId,
            items = emptyList(),
            currency = mapOf(currency to amount),
            xp = xp,
            createdAt = clock.instant(),
        )
        bridge.enqueue(bundle)
    }

    public companion object {
        public const val DEFAULT_STAR: Long = 25L
        public const val DEFAULT_XP: Long = 10L
    }
}
