package com.astarworks.astera.stats.application.port.outbound

import com.astarworks.astera.core.application.port.outbound.IPlayerUnlockStore
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.domain.level.LevelReward

public interface ILevelRewardRepository : IPlayerUnlockStore {
    /** Claim, currency credits and unlock inserts commit together. False means already claimed. */
    public suspend fun apply(playerId: PlayerId, level: Int, reward: LevelReward): Result<Boolean, LevelRewardError>
}

public sealed interface LevelRewardError {
    public data object LedgerUnavailable : LevelRewardError
    public data class Storage(val detail: String) : LevelRewardError
}
