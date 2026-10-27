package com.astarworks.astera.match.application.eventbus.listeners.match

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.MatchSummaryReady
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.match.result.MatchOutcome
import com.astarworks.astera.core.domain.match.result.MatchSummary
import com.astarworks.astera.core.domain.match.result.MvpRule
import com.astarworks.astera.core.domain.match.result.PlayerMatchLine
import com.astarworks.astera.core.domain.match.result.TeamResult
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.service.result.MatchTallyService
import com.astarworks.astera.match.domain.norma.NormaStanding
import org.slf4j.LoggerFactory

/**
 * On [MatchEnded] builds the [MatchSummary] and shows every participant the
 * result screen (F3-4); then publishes [MatchSummaryReady] so the economy can
 * pay out and the last-match record can be written.
 *
 * # What a participant sees
 *
 *  1. A big title: `VICTORY!` / `DEFEAT` / `DRAW` (`astera.match.summary.*`).
 *  2. Chat: header, why the match ended, the team scores, the top players
 *     with their kills / deaths / assists / objectives, the MVP, and the
 *     player's own line. The reward breakdown (currency, XP, level) follows
 *     as the economy handles [MatchSummaryReady].
 *
 * The screen is shown on the game server before the transfer back to the lobby
 * ([com.astarworks.astera.match.application.service.matchmaking.CrossServerSettings.returnDelaySeconds]
 * later); the lobby keeps the numbers in the last-match record
 * (`/astera stats last`).
 *
 * MVP and ranking: see [MvpRule].
 *
 * Match unknown (already destroyed by a concurrent listener) is logged and
 * dropped. A single player's HUD or chat failure never stops the others.
 */
public class MatchEndSummaryListener(
    private val matches: IMatchRepository,
    private val hudGateway: IHudGateway,
    private val messages: IMessageRenderer,
    private val playerGateway: IPlayerGateway,
    private val tally: MatchTallyService,
    private val eventBus: IDomainEventBus,
    private val nameOf: (PlayerId) -> String?,
    /** Final per-team Norma standings of a match (empty outside a Norma match); the result screen shows them. */
    private val normaStandings: (MatchId) -> List<NormaStanding> = { emptyList() },
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: MatchEnded) {
        val match = matches.findById(event.matchId) ?: run {
            log.info("MatchEndSummary: match {} not found in repo at MatchEnded time; skipping.", event.matchId)
            return
        }
        val summary = buildSummary(match, event)
        val standings = normaStandings(event.matchId)

        for (team in match.teams) {
            for (member in team.members) {
                pushTitle(member, summary.outcomeOf(member), event, match, viewerReasonSubtitle(member, match, event))
                pushChat(member, match, summary, event, standings)
            }
        }
        eventBus.publish(MatchSummaryReady(summary))
    }

    internal fun buildSummary(match: Match, event: MatchEnded): MatchSummary {
        val modeKey = tally.modeKeyOf(match.id)
        val counts = tally.take(match.id)
        val lines = match.teams.flatMap { team ->
            team.members.map { member ->
                val c = counts[member] ?: MatchTallyService.Counts()
                PlayerMatchLine(
                    playerId = member,
                    name = nameOf(member) ?: member.uuid.toString().take(8),
                    team = team.id,
                    kills = c.kills,
                    deaths = c.deaths,
                    assists = c.assists,
                    objective = c.objective,
                )
            }
        }
        return MatchSummary(
            matchId = match.id,
            modeKey = modeKey,
            winningTeam = event.winningTeam,
            teams = match.teams.map { TeamResult(it.id, match.score.valueFor(it.id), it.members.size) },
            players = lines,
            mvp = MvpRule.pick(lines, event.winningTeam),
            durationSeconds = ((event.atTick - match.startedAtTick).coerceAtLeast(0L)) / TICKS_PER_SECOND,
        )
    }

    /** One-line "why it ended" shown under the verdict title (new short keys `astera.match.summary.subtitle.*`). */
    private fun viewerReasonSubtitle(viewer: PlayerId, match: Match, event: MatchEnded): HudComponent {
        val teamsById = match.teams.associateBy { it.id }
        fun team(id: TeamId?): Map<String, String> = mapOf(
            "team" to (id?.let { teamsById[it] }?.let { messages.render(viewer, it.displayNameKey) } ?: id?.value.orEmpty()),
            "color" to teamColor(viewer, id?.let { teamsById[it] }),
        )
        val reason = event.reason
        return when (reason) {
            is EndReason.NormaLevelReached -> HudComponent.Text(MessageKey("astera.match.summary.subtitle.norma-level"), placeholders = team(reason.winner) + ("level" to reason.level.toString()))
            is EndReason.NormaTimeLimit ->
                if (reason.leader != null) HudComponent.Text(MessageKey("astera.match.summary.subtitle.norma-time"), placeholders = team(reason.leader))
                else HudComponent.Text(MessageKey("astera.match.summary.subtitle.time-draw"))
            is EndReason.EncounterCleared -> HudComponent.Text(MessageKey("astera.match.summary.subtitle.pve-cleared"), placeholders = mapOf("waves" to reason.waves.toString()))
            is EndReason.EncounterFailed -> HudComponent.Text(
                MessageKey("astera.match.summary.subtitle.pve-failed"),
                placeholders = mapOf("wave" to reason.wave.toString(), "waves" to reason.waves.toString()),
            )
            else -> when (reasonKindOf(event)) {
                "objective" -> event.winningTeam?.let { HudComponent.Text(MessageKey("astera.match.summary.subtitle.objective"), placeholders = team(it)) }
                    ?: HudComponent.Text(MessageKey("astera.match.summary.subtitle.other"))
                "timer-expired" -> HudComponent.Text(MessageKey("astera.match.summary.subtitle.timer"))
                else -> HudComponent.Text(MessageKey("astera.match.summary.subtitle.other"))
            }
        }
    }

    private fun pushTitle(playerId: PlayerId, outcome: MatchOutcome, event: MatchEnded, match: Match, subtitle: HudComponent) {
        val matchId = event.matchId
        val layout = HudLayout.Subtitle(
            title = HudComponent.Text(messageKey = verdictKey(outcome)),
            component = subtitle,
            fadeInTicks = SUMMARY_FADE_IN,
            stayTicks = SUMMARY_STAY,
            fadeOutTicks = SUMMARY_FADE_OUT,
        )
        when (val outcome2 = hudGateway.showFor(playerId, layout)) {
            is Result.Success -> Unit
            is Result.Failure -> log.debug(
                "MatchEndSummary title failed for {} in match {}: {}", playerId, matchId, outcome2.error,
            )
        }
    }

    private fun pushChat(viewer: PlayerId, match: Match, summary: MatchSummary, event: MatchEnded, standings: List<NormaStanding>) {
        fun send(key: String, vararg params: Pair<String, String>) =
            playerGateway.sendMessage(viewer, messages.render(viewer, MessageKey(key), params.toMap()))

        val teamsById = match.teams.associateBy { it.id }
        fun teamLabel(team: TeamId, template: String, extra: Map<String, String> = emptyMap()): String {
            val t = teamsById[team]
            val color = t?.let { messages.render(viewer, it.colorKey) }?.takeIf { it in COLORS } ?: "white"
            val name = t?.let { messages.render(viewer, it.displayNameKey) } ?: team.value
            return messages.render(viewer, MessageKey(template), extra + mapOf("team" to name, "color" to color))
        }

        send("astera.match.summary.header")
        val reason = event.reason
        when (reason) {
            is EndReason.NormaLevelReached ->
                send("astera.match.summary.reason.norma-level-reached", *teamParams(viewer, teamsById[reason.winner], reason.winner.value), "level" to reason.level.toString())
            is EndReason.NormaTimeLimit -> {
                val leader = reason.leader
                if (leader != null) {
                    send("astera.match.summary.reason.norma-time-limit", *teamParams(viewer, teamsById[leader], leader.value))
                } else {
                    send("astera.match.summary.reason.norma-time-draw")
                }
            }
            is EndReason.EncounterCleared -> send("astera.match.summary.reason.encounter-cleared", "waves" to reason.waves.toString())
            is EndReason.EncounterFailed -> send(
                if (reason.timeUp) "astera.match.summary.reason.encounter-time-up" else "astera.match.summary.reason.encounter-failed",
                "wave" to reason.wave.toString(),
                "waves" to reason.waves.toString(),
            )
            else -> {
                // "Ended by objective completion" tells nobody anything: name the winner and the mode's own objective.
                val winner = event.winningTeam
                val modeReason = summary.modeKey.takeIf { it.isNotBlank() && winner != null && reasonKindOf(event) == "objective" }
                    ?.let { "astera.match.summary.reason.objective-by.$it" }
                    ?.let { key -> teamParams(viewer, teamsById[winner], winner!!.value).let { params -> key to params } }
                    ?.let { (key, params) -> key to messages.render(viewer, MessageKey(key), params.toMap()) }
                    ?.takeIf { (key, text) -> text.isNotBlank() && text != key && !text.startsWith("astera.") }
                if (modeReason != null) playerGateway.sendMessage(viewer, modeReason.second)
                else send("astera.match.summary.reason.${reasonKindOf(event)}")
            }
        }

        // Score line: "Score  Red 3 - Blue 5".
        val scores = summary.teams.joinToString(SCORE_SEPARATOR) { r ->
            teamLabel(r.team, "astera.match.result.score.team", mapOf("score" to r.score.toString()))
        }
        send("astera.match.result.score.label", "scores" to scores)

        // Norma standing per team: the tie-break order of the time limit (level, stars, kills), so a loser sees how close it was.
        if (standings.isNotEmpty()) {
            val line = standings.joinToString(SCORE_SEPARATOR) { st ->
                teamLabel(
                    st.team, "astera.match.result.norma.team",
                    mapOf("level" to st.level.toString(), "max" to st.maxLevel.toString(), "stars" to st.stars.toString(), "kills" to st.kills.toString()),
                )
            }
            send("astera.match.result.norma.label", "standings" to line)
        }

        // Top players.
        val top = summary.topPlayers(TOP_PLAYERS)
        if (top.isNotEmpty()) {
            send("astera.match.result.top.header")
            top.forEachIndexed { index, line ->
                val mvpTag = if (line.playerId == summary.mvp) messages.render(viewer, MessageKey("astera.match.result.mvp-tag")) else ""
                send(
                    "astera.match.result.top.entry",
                    "rank" to (index + 1).toString(),
                    "name" to line.name,
                    "color" to teamColor(viewer, teamsById[line.team]),
                    "kills" to line.kills.toString(),
                    "deaths" to line.deaths.toString(),
                    "assists" to line.assists.toString(),
                    "objective" to line.objective.toString(),
                    "mvp" to mvpTag,
                )
            }
        }

        // MVP.
        summary.players.firstOrNull { it.playerId == summary.mvp }?.let { mvp ->
            send(
                "astera.match.result.mvp",
                "name" to mvp.name,
                "color" to teamColor(viewer, teamsById[mvp.team]),
                "kills" to mvp.kills.toString(),
                "deaths" to mvp.deaths.toString(),
                "assists" to mvp.assists.toString(),
            )
        }

        // The viewer's own line.
        summary.players.firstOrNull { it.playerId == viewer }?.let { me ->
            send(
                "astera.match.result.you",
                "kills" to me.kills.toString(),
                "deaths" to me.deaths.toString(),
                "assists" to me.assists.toString(),
                "objective" to me.objective.toString(),
            )
        }

        // A loser is told what would have won (the Norma race is the one rule every mode shares).
        if (standings.isNotEmpty() && summary.outcomeOf(viewer) == MatchOutcome.LOSE) {
            send("astera.match.result.hint", "max" to standings.first().maxLevel.toString())
        }

        // Clickable line that re-opens this card later from anywhere (the lobby keeps it as the last-match record).
        send("astera.match.result.open-last")
    }

    private fun teamParams(viewer: PlayerId, team: com.astarworks.astera.core.domain.match.Team?, fallbackName: String): Array<Pair<String, String>> =
        arrayOf(
            "team" to (team?.let { messages.render(viewer, it.displayNameKey) } ?: fallbackName),
            "color" to teamColor(viewer, team),
        )

    private fun teamColor(viewer: PlayerId, team: com.astarworks.astera.core.domain.match.Team?): String =
        team?.let { messages.render(viewer, it.colorKey) }?.takeIf { it in COLORS } ?: "white"

    private fun verdictKey(outcome: MatchOutcome): MessageKey = when (outcome) {
        MatchOutcome.WIN -> MSG_VICTORY
        MatchOutcome.LOSE -> MSG_DEFEAT
        MatchOutcome.DRAW -> MSG_DRAW
    }

    /**
     * Stable lowercase snake_case discriminator for the reason — kept in this
     * listener so i18n key naming doesn't leak into [EndReason].
     */
    private fun reasonKindOf(event: MatchEnded): String =
        when (event.reason) {
            EndReason.ScoreThresholdReached -> "score-threshold"
            EndReason.TimerExpired -> "timer-expired"
            EndReason.AdminEnded -> "admin"
            EndReason.SuddenDeathResolved -> "sudden-death"
            EndReason.ObjectiveCompleted -> "objective"
            // WorldUnloaded is distinct from Cancelled so summary screens can branch on it.
            EndReason.WorldUnloaded -> "world-unloaded"
            is EndReason.Cancelled -> "cancelled"
            is EndReason.TeamVictory -> "objective"
            // Norma results are named by pushChat (they carry the team); this is only the generic fallback.
            is EndReason.NormaLevelReached, is EndReason.NormaTimeLimit -> "score-threshold"
            // PvE results are named by pushChat (they carry the wave counts); this is only the generic fallback.
            is EndReason.EncounterCleared -> "encounter-cleared"
            is EndReason.EncounterFailed -> "encounter-failed"
        }

    public companion object {
        public val MSG_VICTORY: MessageKey = MessageKey("astera.match.summary.victory")
        public val MSG_DEFEAT: MessageKey = MessageKey("astera.match.summary.defeat")
        public val MSG_DRAW: MessageKey = MessageKey("astera.match.summary.draw")

        public const val SUMMARY_FADE_IN: Int = 10
        public const val SUMMARY_STAY: Int = 130
        public const val SUMMARY_FADE_OUT: Int = 20

        /** Rows in the chat top-players table. */
        public const val TOP_PLAYERS: Int = 5

        private const val TICKS_PER_SECOND: Long = 20L
        private const val SCORE_SEPARATOR: String = " <gray>-</gray> "

        /** MiniMessage named colours a team colour key may resolve to. */
        private val COLORS: Set<String> = setOf(
            "red", "blue", "yellow", "green", "gold", "aqua", "white", "gray",
            "dark_red", "dark_blue", "dark_green", "dark_aqua", "dark_purple", "light_purple",
        )
    }
}
