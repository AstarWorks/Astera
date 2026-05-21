package com.astarworks.astera.stats.application.eventbus.listeners.advancement

import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.inbound.IAdvancementService
import com.astarworks.astera.core.application.port.outbound.IAdvancementRegistry
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.advancement.AdvancementCriteria
import com.astarworks.astera.core.domain.advancement.CustomAdvancement
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.ItemAcquired
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.event.CurrencyChanged
import org.slf4j.LoggerFactory

/**
 * Routes cross-plugin domain events to the Wave 5p advancement progress
 * service (ADR-0088).
 *
 * # Why one fan-out class
 *
 * Wave 5a–5l each shipped per-event listeners (`MissionProgressOnKillListener`,
 * `RewardOnKillListener`, …). For advancement progress the routing is the
 * same shape four times — "find every advancement whose criteria leaf matches
 * this event signal and call `IAdvancementService.progress(...)`". One
 * listener keeps the cross-cutting policy in one file and the per-event
 * subscription wiring (Phase C in `AsteraStatsPlugin`) short.
 *
 * # Event ↔ criterion map
 *
 *  - [PlayerKilled] → [AdvancementCriteria.KillCount] for the killer
 *    (suicides where `killer == null` are ignored).
 *  - [CurrencyChanged] → [AdvancementCriteria.CurrencyEarned] when
 *    `delta > 0` (the amount counts as "earned"); positive deltas only.
 *  - [MatchEnded] → [AdvancementCriteria.MatchesWon] for every member of
 *    the winning team (no winning team → no progress).
 *  - [ItemAcquired] → [AdvancementCriteria.ItemAcquired] when [ItemId]
 *    matches a leaf.
 *
 * # Composite criteria
 *
 * The listener walks the criterion tree and triggers a progress call for
 * the **first** matching leaf — `ProgressAdvancementUseCase` handles the
 * composite roll-up internally (it stores per-leaf rows and evaluates the
 * full tree on completion).
 *
 * # Failure handling
 *
 * `progress(...)` returns `Result<AdvancementProgress, AdvancementError>`;
 * failures are logged at debug and swallowed — one bad advancement must not
 * block the others.
 */
public class TrackAdvancementProgressListener(
    private val service: IAdvancementService,
    private val registry: IAdvancementRegistry,
    private val matchRepository: IMatchRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: PlayerKilled) {
        val killer = event.killer ?: return
        forEachMatching(killer) { advancement ->
            firstLeaf(advancement.criteria) { leaf ->
                leaf is AdvancementCriteria.KillCount
            }?.let { _ ->
                progressSafe(killer, advancement)
            }
        }
    }

    public fun handle(event: CurrencyChanged) {
        if (event.delta <= 0) return
        forEachMatching(event.playerId) { advancement ->
            firstLeaf(advancement.criteria) { leaf ->
                leaf is AdvancementCriteria.CurrencyEarned && leaf.currency == event.currency
            }?.let {
                // CurrencyEarned increments by amount (capped at Int.MAX).
                progressSafe(
                    event.playerId,
                    advancement,
                    increment = event.delta.toInt().coerceAtLeast(1),
                )
            }
        }
    }

    public fun handle(event: MatchEnded) {
        val winningTeamId = event.winningTeam ?: return
        val match = matchRepository.findById(event.matchId) ?: run {
            log.debug("TrackAdvancementProgressListener: match {} not found", event.matchId)
            return
        }
        val winningTeam = match.teams.firstOrNull { it.id == winningTeamId } ?: return
        for (playerId in winningTeam.members) {
            forEachMatching(playerId) { advancement ->
                firstLeaf(advancement.criteria) { it is AdvancementCriteria.MatchesWon }
                    ?.let { progressSafe(playerId, advancement) }
            }
        }
    }

    public fun handle(event: ItemAcquired) {
        forEachMatching(event.playerId) { advancement ->
            firstLeaf(advancement.criteria) { leaf ->
                leaf is AdvancementCriteria.ItemAcquired && leaf.itemId == event.itemId
            }?.let { progressSafe(event.playerId, advancement) }
        }
    }

    /** Iterate every advancement in the catalog, applying [block]. */
    private inline fun forEachMatching(@Suppress("UNUSED_PARAMETER") playerId: PlayerId, block: (CustomAdvancement) -> Unit) {
        for (advancement in registry.all()) {
            block(advancement)
        }
    }

    /** Pre-order search for the first leaf matching [predicate]. */
    private fun firstLeaf(
        criteria: AdvancementCriteria,
        predicate: (AdvancementCriteria) -> Boolean,
    ): AdvancementCriteria? = when (criteria) {
        is AdvancementCriteria.AllOf -> criteria.children.firstNotNullOfOrNull { firstLeaf(it, predicate) }
        is AdvancementCriteria.AnyOf -> criteria.children.firstNotNullOfOrNull { firstLeaf(it, predicate) }
        else -> if (predicate(criteria)) criteria else null
    }

    private fun progressSafe(
        playerId: PlayerId,
        advancement: CustomAdvancement,
        increment: Int = 1,
    ) {
        runCatching { service.progress(playerId, advancement.id, increment) }
            .onFailure { err ->
                log.debug(
                    "TrackAdvancementProgressListener: progress failed player={} advancement={} err={}",
                    playerId, advancement.id, err.message,
                )
            }
    }

}
