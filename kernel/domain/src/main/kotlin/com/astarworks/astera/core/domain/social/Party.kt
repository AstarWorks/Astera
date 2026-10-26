package com.astarworks.astera.core.domain.social

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Pre-match grouping of players that queues + spawns together (Wave 5e).
 *
 * Worker β fills the join/leave/disband state transitions. Phase A defines
 * the value record and immutable ops are the convention (every mutation
 * returns a new [Party]).
 */
public data class Party(
    val id: PartyId,
    val leader: PlayerId,
    val members: Set<PlayerId>,
    val maxSize: Int,
) {
    init {
        require(maxSize >= 1) { "Party.maxSize must be >= 1: $maxSize" }
        require(members.size <= maxSize) {
            "Party members (${members.size}) exceeds maxSize ($maxSize)"
        }
        require(leader in members) { "Party leader must be a member" }
    }
}
