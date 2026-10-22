package com.astarworks.astera.core.domain.proxy

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Proof that [fromServer] has durably saved [playerId]'s state before moving
 * them away (F3-6). [version] is the epoch-millisecond timestamp of the saved
 * snapshot and strictly increases per player, so the receiving server restores
 * only a snapshot saved at or after it and never a stale one.
 */
public data class HandoffToken(
    val playerId: PlayerId,
    val version: Long,
    val fromServer: ServerName,
)
