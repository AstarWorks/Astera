package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.application.port.outbound.IMatchLevelGate
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * [IMatchLevelGate] backed by the in-match Norma level (ADR-0273). A player outside a Norma match is never gated.
 * [onDenied] gives the player the "unlocks at Lv N" feedback.
 */
public class NormaLevelGate(
    private val norma: NormaLevelService,
    private val onDenied: (PlayerId, Int) -> Unit,
) : IMatchLevelGate {
    override fun denyIfLocked(playerId: PlayerId, requiredLevel: Int): Boolean {
        val level = norma.levelOf(playerId) ?: return false
        if (level >= requiredLevel) return false
        onDenied(playerId, requiredLevel)
        return true
    }
}
