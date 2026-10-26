package com.astarworks.astera.core.domain.afk

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Snapshot of a player currently in warning or AFK state. Returned by
 * [com.astarworks.astera.core.application.port.outbound.afk.IAfkDetector.activeAfkers]
 * for HUD / admin tooling.
 */
public data class AfkRecord(
    val playerId: PlayerId,
    val sinceTick: Long,
    val lastActivityTick: Long,
)
