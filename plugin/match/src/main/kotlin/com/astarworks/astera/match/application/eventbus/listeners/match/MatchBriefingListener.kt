package com.astarworks.astera.match.application.eventbus.listeners.match

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.domain.event.PhaseChanged
import com.astarworks.astera.match.domain.norma.NormaConfig

/**
 * "How to win" briefing: when a match turns ACTIVE every participant gets a short chat block that says what the
 * mode's own objective is, how Norma levels work (the sidebar shows the player's current goal), whether reaching the
 * top level wins outright, and what decides the match if the time limit runs out. The first-time-player yardstick is
 * "understands how Norma wins the match in ten minutes"; before this the only teaching was the optional tutorial.
 *
 * The mode line is `astera.match.briefing.mode.<modeKey>`; a mode without one just gets the Norma lines.
 */
public class MatchBriefingListener(
    private val matches: IMatchRepository,
    private val playerGateway: IPlayerGateway,
    private val messages: IMessageRenderer,
    private val modeKeyOf: (MatchId) -> String?,
    private val configOf: (MatchId) -> Map<String, String>,
    private val isNorma: (MatchId) -> Boolean,
) {

    public fun onPhaseChanged(event: PhaseChanged) {
        if (event.to != MatchPhase.ACTIVE) return
        val match = matches.findById(event.matchId) ?: return
        val members = match.teams.flatMap { it.members }
        if (members.isEmpty()) return
        val config = configOf(event.matchId)
        val norma = if (isNorma(event.matchId)) NormaConfig.parse(config) else null
        val timeLimitMinutes = (config["time_limit_seconds"]?.toLongOrNull() ?: DEFAULT_TIME_LIMIT_SECONDS) / SECONDS_PER_MINUTE
        val modeKey = modeKeyOf(event.matchId)?.takeIf { it.isNotBlank() }
        for (member in members) brief(member, modeKey, norma, timeLimitMinutes)
    }

    private fun brief(viewer: PlayerId, modeKey: String?, norma: NormaConfig?, timeLimitMinutes: Long) {
        fun send(key: String, vararg params: Pair<String, String>) =
            playerGateway.sendMessage(viewer, messages.render(viewer, MessageKey(key), params.toMap()))

        send("astera.match.briefing.header")
        if (modeKey != null) {
            val key = "astera.match.briefing.mode.$modeKey"
            val text = messages.render(viewer, MessageKey(key))
            if (text.isNotBlank() && text != key && !text.startsWith("astera.")) playerGateway.sendMessage(viewer, text)
        }
        if (norma == null) return
        send(
            if (norma.win) "astera.match.briefing.norma-win" else "astera.match.briefing.norma-rank",
            "max" to norma.maxLevel.toString(),
        )
        if (timeLimitMinutes > 0) send("astera.match.briefing.time", "minutes" to timeLimitMinutes.toString())
    }

    private companion object {
        const val DEFAULT_TIME_LIMIT_SECONDS = 600L
        const val SECONDS_PER_MINUTE = 60L
    }
}
