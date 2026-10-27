package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Norma level gate (ADR-0288): may [playerId] use something that unlocks at Norma level N?
 *
 * Lives in core so the Items plugin can ask without depending on the Match plugin (ADR-0022); Match implements it
 * with the player's in-match Norma level. Absent registration, or a player who is not in a Norma match, means no
 * gate (everything is usable).
 */
public fun interface IMatchLevelGate {
    /**
     * `true` when [playerId] is below [requiredLevel] and the use must be refused. The implementation has already
     * given the feedback (action bar "unlocks at Lv N" plus a deny sound), so the caller just drops the action.
     */
    public fun denyIfLocked(playerId: PlayerId, requiredLevel: Int): Boolean
}
