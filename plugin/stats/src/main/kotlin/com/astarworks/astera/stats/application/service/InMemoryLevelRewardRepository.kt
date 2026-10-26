package com.astarworks.astera.stats.application.service

import kotlinx.coroutines.sync.withLock
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
    private val mutex = kotlinx.coroutines.sync.Mutex()

    override suspend fun apply(playerId: PlayerId, level: Int, reward: LevelReward): Result<Boolean, LevelRewardError> = mutex.withLock {
        if (playerId to level in claims) return@withLock Result.success(false)
        val wallet = ledger() ?: return@withLock Result.failure(LevelRewardError.LedgerUnavailable)
        wallet.credit(playerId, Currency.GOLD, reward.gold)
        owned.getOrPut(playerId) { mutableSetOf() }.addAll(reward.unlocks)
        claims += playerId to level
        Result.success(true)
    }

    override suspend fun unlocks(playerId: PlayerId): Set<String> = mutex.withLock { owned[playerId]?.toSet() ?: emptySet() }

    override suspend fun grant(playerId: PlayerId, unlockId: String): Boolean =
        mutex.withLock { owned.getOrPut(playerId) { mutableSetOf() }.add(unlockId) }
}
