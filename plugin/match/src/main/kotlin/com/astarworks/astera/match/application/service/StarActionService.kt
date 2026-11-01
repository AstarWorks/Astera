package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.port.outbound.IGeneratorRepository
import com.astarworks.astera.match.domain.star.StarSource

/**
 * Action-based star issuance (ADR-0318): mining a Mid ore node and breaking a block of the content-listed set
 * (`star_blocks` in the layer) pay the actor directly, within the per-player caps of the [StarBudget]. Only matches that
 * run the star economy (a stage with spot, wave or cache generators) pay; anywhere else the call does nothing.
 */
public class StarActionService(
    private val matches: IMatchRepository,
    private val generators: IGeneratorRepository,
    private val issuer: StarIssuer,
    private val clock: StarSupplyClock,
) {
    /** [player] finished mining an ore node. Returns the stars granted. */
    public fun onOreMined(player: PlayerId): Int {
        val matchId = economyMatchOf(player) ?: return 0
        return issuer.issue(matchId, player, StarSource.MINING, clock.economyOf(matchId).miningStars)
    }

    /** [player] broke a block of [material] (a Bukkit material name). Returns the stars granted. */
    public fun onBlockBroken(player: PlayerId, material: String): Int {
        val matchId = economyMatchOf(player) ?: return 0
        val economy = clock.economyOf(matchId)
        if (material.uppercase() !in economy.blockMaterials) return 0
        return issuer.issue(matchId, player, StarSource.BLOCKS, economy.blockStars)
    }

    private fun economyMatchOf(player: PlayerId): MatchId? {
        val match = matches.findMatchOf(player)?.takeIf { it.phase == MatchPhase.ACTIVE || it.phase == MatchPhase.SUDDEN_DEATH } ?: return null
        return match.id.takeIf { runsEconomy(generators, it) }
    }

    public companion object {
        /** A match runs the star economy when its stage authored spot, wave or cache generators. */
        public fun runsEconomy(generators: IGeneratorRepository, matchId: MatchId): Boolean =
            generators.listForMatch(matchId).any { it.dropItem == null && (it.spots.isNotEmpty() || it.wave != null || it.cache != null) }
    }
}
