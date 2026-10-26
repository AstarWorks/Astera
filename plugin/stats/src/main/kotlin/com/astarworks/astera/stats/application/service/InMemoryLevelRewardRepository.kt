package com.astarworks.astera.stats.application.service

import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.port.outbound.ILevelRewardRepository
import com.astarworks.astera.stats.application.port.outbound.LevelRewardError
import com.astarworks.astera.stats.domain.level.LevelReward

public class InMemoryLevelRewardRepository(private val ledger: () -> ICurrencyLedger?) : ILevelRewardRepository {
    private val claims = mutableSetOf<Pair<PlayerId, Int>>()
    private val owned = mutableMapOf<PlayerId, MutableSet<String>>()

    @Synchronized
    override fun apply(playerId: PlayerId, level: Int, reward: LevelReward): Result<Boolean, LevelRewardError> {
        if (playerId to level in claims) return Result.success(false)
        val wallet = ledger() ?: return Result.failure(LevelRewardError.LedgerUnavailable)
        wallet.credit(playerId, Currency.GOLD, reward.gold)
        owned.getOrPut(playerId) { mutableSetOf() }.addAll(reward.unlocks)
        claims += playerId to level
        return Result.success(true)
    }

    @Synchronized
    override fun unlocks(playerId: PlayerId): Set<String> = owned[playerId]?.toSet() ?: emptySet()

    @Synchronized
    override fun grant(playerId: PlayerId, unlockId: String): Boolean =
        owned.getOrPut(playerId) { mutableSetOf() }.add(unlockId)
}
