package com.astarworks.astera.stats.application.eventbus.listeners

import com.astarworks.astera.core.domain.event.PlayerRatingUpdated
import com.astarworks.astera.stats.application.port.outbound.ILeaderboardRepository
import com.astarworks.astera.stats.application.port.outbound.ISeasonRepository
import org.slf4j.LoggerFactory

/**
 * On [PlayerRatingUpdated] materialises the per-season leaderboard: records the
 * new rating + W/L tally for the player in whichever season is currently active
 * (Wave 7b, ADR-0037).
 *
 * No-ops when no season is active — ratings can still change outside a season
 * window, they simply do not land on a leaderboard until one is open.
 *
 * silly-knuth §5: in-process only, no broadcaster.
 */
public class RecordRatingOnPlayerRatingUpdatedListener(
    private val seasonRepository: ISeasonRepository,
    private val leaderboardRepository: ILeaderboardRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public suspend fun handle(event: PlayerRatingUpdated) {
        val season = seasonRepository.current()
        if (season == null) {
            log.debug(
                "RecordRatingOnPlayerRatingUpdatedListener: no active season, dropping rating update for player={}",
                event.playerId,
            )
            return
        }
        runCatching {
            leaderboardRepository.apply(season.id, event.playerId, event.newRating, event.won)
        }.onFailure { err ->
            log.debug(
                "RecordRatingOnPlayerRatingUpdatedListener: failed for player={} season={} err={}",
                event.playerId, season.id, err.message,
            )
        }
    }
}
