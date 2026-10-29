package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Read-only account progress for sidebar-style displays, published by `:plugin:stats`
 * so the lobby sidebar can draw the level / XP bar and achievement progress without
 * depending on the stats plugin. Only reads existing level / achievement data.
 */
public interface IPlayerProgressView {
    public fun progressOf(playerId: PlayerId): AccountProgress

    /**
     * @property xpIntoLevel XP earned since the current level started.
     * @property xpForLevel XP the whole current level spans (next threshold minus this one).
     * @property achievementsTotal catalog size; 0 when there are no achievements to show.
     */
    public data class AccountProgress(
        val level: Int,
        val xpIntoLevel: Long,
        val xpForLevel: Long,
        val achievementsUnlocked: Int,
        val achievementsTotal: Int,
    )
}
