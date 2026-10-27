package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.application.port.outbound.IMatchLevelGate
import com.astarworks.astera.core.domain.item.Rarity
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.domain.norma.NormaConfig

/**
 * [IMatchLevelGate] backed by the in-match Norma level and the layer's `rarity_levels` (ADR-0289). A player outside a
 * Norma match is never gated. [layerDefaults] answers [requiredLevel] where no match is known (item tooltips);
 * [onDenied] gives the player the "Lv N で <rarity> 解放" feedback.
 */
public class NormaLevelGate(
    private val norma: NormaLevelService,
    private val layerDefaults: () -> NormaConfig,
    private val onDenied: (PlayerId, Int, Rarity) -> Unit,
) : IMatchLevelGate {
    override fun requiredLevel(rarity: Rarity): Int = layerDefaults().levelFor(rarity)

    override fun denyIfLocked(playerId: PlayerId, rarity: Rarity): Boolean {
        val level = norma.levelOf(playerId) ?: return false
        val required = (norma.normaConfigOf(playerId) ?: return false).levelFor(rarity)
        if (level >= required) return false
        onDenied(playerId, required, rarity)
        return true
    }
}
