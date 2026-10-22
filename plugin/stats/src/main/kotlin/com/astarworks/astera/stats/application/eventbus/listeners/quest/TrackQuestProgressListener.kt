package com.astarworks.astera.stats.application.eventbus.listeners.quest

import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.inbound.IQuestService
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IQuestChainRegistry
import com.astarworks.astera.core.domain.advancement.AdvancementCriteria
import com.astarworks.astera.core.domain.event.CurrencyChanged
import com.astarworks.astera.core.domain.event.ItemAcquired
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.MobKilled
import com.astarworks.astera.core.domain.player.PlayerId
import org.slf4j.LoggerFactory

/**
 * Drives quest chains from real events (ADR-0210). Until this existed nothing called
 * [IQuestService.progressCurrent], so no quest stage could ever clear.
 *
 * For every chain the player has started and not finished, the *current stage's* criteria decide
 * whether an event counts: [PlayerKilled] / [MobKilled] -> `kill_count`, [MatchEnded] (members of the winning
 * team) -> `matches_won`, positive [CurrencyChanged] -> `currency_earned` (same currency),
 * [ItemAcquired] -> `item_acquired` (same item). Composite criteria count when any leaf matches.
 * Stage rewards, the next stage and chain completion are handled by the quest service.
 */
public class TrackQuestProgressListener(
    private val quests: IQuestService,
    private val chains: IQuestChainRegistry,
    private val matchRepository: IMatchRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: PlayerKilled) {
        val killer = event.killer ?: return
        progress(killer, 1) { it is AdvancementCriteria.KillCount }
    }

    public fun handle(event: MobKilled) {
        val killer = event.killerId ?: return
        progress(killer, 1) { it is AdvancementCriteria.KillCount }
    }

    public fun handle(event: MatchEnded) {
        val winningTeam = event.winningTeam ?: return
        val match = matchRepository.findById(event.matchId) ?: return
        val winners = match.teams.firstOrNull { it.id == winningTeam }?.members ?: return
        for (playerId in winners) progress(playerId, 1) { it is AdvancementCriteria.MatchesWon }
    }

    public fun handle(event: CurrencyChanged) {
        if (event.delta <= 0L) return
        progress(event.playerId, event.delta.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) {
            it is AdvancementCriteria.CurrencyEarned && it.currency == event.currency
        }
    }

    public fun handle(event: ItemAcquired) {
        progress(event.playerId, 1) { it is AdvancementCriteria.ItemAcquired && it.itemId == event.itemId }
    }

    private fun progress(playerId: PlayerId, increment: Int, matches: (AdvancementCriteria) -> Boolean) {
        for (row in quests.activeChains(playerId)) {
            val stage = chains.find(row.chainId)?.stage(row.currentStageId) ?: continue
            if (!anyLeaf(stage.criteria, matches)) continue
            quests.progressCurrent(playerId, row.chainId, increment).onFailure {
                log.debug("TrackQuestProgressListener: {} for {} failed: {}", row.chainId, playerId, it)
            }
        }
    }

    private fun anyLeaf(criteria: AdvancementCriteria, matches: (AdvancementCriteria) -> Boolean): Boolean = when (criteria) {
        is AdvancementCriteria.AllOf -> criteria.children.any { anyLeaf(it, matches) }
        is AdvancementCriteria.AnyOf -> criteria.children.any { anyLeaf(it, matches) }
        else -> matches(criteria)
    }
}
