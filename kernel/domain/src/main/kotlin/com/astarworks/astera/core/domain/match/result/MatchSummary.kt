package com.astarworks.astera.core.domain.match.result

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId

/** How a match ended for one player. */
public enum class MatchOutcome { WIN, LOSE, DRAW }

/**
 * One participant's line in the end-of-match result: what they did in this
 * match only (not lifetime stats).
 */
public data class PlayerMatchLine(
    val playerId: PlayerId,
    val name: String,
    val team: TeamId,
    val kills: Int,
    val deaths: Int,
    val assists: Int,
    val objective: Int,
) {
    init {
        require(kills >= 0 && deaths >= 0 && assists >= 0 && objective >= 0) { "match tallies must be >= 0" }
    }
}

/** One team's final standing. */
public data class TeamResult(val team: TeamId, val score: Int, val members: Int)

/**
 * The finished match as the result screen, the reward calculation and the
 * "last match" record all see it. Built once per match by the match plugin
 * and published as `MatchSummaryReady`.
 *
 * @property modeKey game-mode id (`battle-loyal`, `annihilation`, ...), blank when unknown.
 * @property winningTeam `null` is a draw.
 * @property mvp the single best player of the match, `null` when nobody scored.
 */
public data class MatchSummary(
    val matchId: MatchId,
    val modeKey: String,
    val winningTeam: TeamId?,
    val teams: List<TeamResult>,
    val players: List<PlayerMatchLine>,
    val mvp: PlayerId?,
    val durationSeconds: Long,
) {
    /** [player]'s outcome; a player not in [players] is a loser by convention. */
    public fun outcomeOf(player: PlayerId): MatchOutcome {
        val team = players.firstOrNull { it.playerId == player }?.team
        return when {
            winningTeam == null -> MatchOutcome.DRAW
            team == winningTeam -> MatchOutcome.WIN
            else -> MatchOutcome.LOSE
        }
    }

    /** Players ranked best first (see [MvpRule.ranking]), at most [limit]. */
    public fun topPlayers(limit: Int): List<PlayerMatchLine> =
        players.sortedWith(MvpRule.ranking).take(limit)
}

/**
 * The MVP rule, deliberately simple:
 *
 *  1. Every player scores `kills * 3 + assists * 1 + objective * 2`
 *     (deaths do not subtract; they only break ties).
 *  2. The MVP is the highest scorer of the **winning team**; on a draw, of
 *     everybody. A match with a winner never crowns a loser.
 *  3. Ties: fewer deaths, then more kills, then the lower player id.
 *
 * Players with a score of 0 are never MVP. The top-players list on the
 * result screen uses the same ranking over everybody (rule 1 and 3 only).
 */
public object MvpRule {
    public const val KILL_POINTS: Int = 3
    public const val ASSIST_POINTS: Int = 1
    public const val OBJECTIVE_POINTS: Int = 2

    public fun score(line: PlayerMatchLine): Int =
        line.kills * KILL_POINTS + line.assists * ASSIST_POINTS + line.objective * OBJECTIVE_POINTS

    /** Best first: score, then fewer deaths, then more kills, then player id. */
    public val ranking: Comparator<PlayerMatchLine> =
        compareByDescending<PlayerMatchLine> { score(it) }
            .thenBy { it.deaths }
            .thenByDescending { it.kills }
            .thenBy { it.playerId.uuid.toString() }

    public fun pick(players: List<PlayerMatchLine>, winningTeam: TeamId?): PlayerId? {
        val pool = if (winningTeam == null) players else players.filter { it.team == winningTeam }
        return pool.filter { score(it) > 0 }.minWithOrNull(ranking)?.playerId
    }
}
