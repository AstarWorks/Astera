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
 * The mode line is `astera.match.briefing.mode.<modeKey>`; a mode without one just gets the Norma lines. The same
 * moment also flashes the objective as a title (`astera.match.objective.<modeKey>.title|subtitle`), and a player whose
 * first match this is gets the short beginner guide (`astera.match.briefing.first-time.*`) once, because it is keyed on
 * their stats' matches-played counter.
 */
public class MatchBriefingListener(
    private val matches: IMatchRepository,
    private val playerGateway: IPlayerGateway,
    private val messages: IMessageRenderer,
    private val modeKeyOf: (MatchId) -> String?,
    private val configOf: (MatchId) -> Map<String, String>,
    private val isNorma: (MatchId) -> Boolean,
    /** Shows a title + subtitle (already resolved against the viewer's language by the Paper layer). */
    private val showTitle: (PlayerId, MessageKey, MessageKey) -> Unit = { _, _, _ -> },
    /** True while the player has never finished a match: they also get the beginner guide. */
    private val isFirstMatch: (PlayerId) -> Boolean = { false },
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

        if (modeKey != null) {
            val titleKey = MessageKey("astera.match.objective.$modeKey.title")
            val rendered = messages.render(viewer, titleKey)
            if (rendered.isNotBlank() && !rendered.startsWith("astera.")) {
                showTitle(viewer, titleKey, MessageKey("astera.match.objective.$modeKey.subtitle"))
            }
        }
        send("astera.match.briefing.header")
        if (modeKey != null) {
            val key = "astera.match.briefing.mode.$modeKey"
            val text = messages.render(viewer, MessageKey(key))
            if (text.isNotBlank() && text != key && !text.startsWith("astera.")) playerGateway.sendMessage(viewer, text)
        }
        if (isFirstMatch(viewer)) {
            send("astera.match.briefing.first-time.header")
            send("astera.match.briefing.first-time.sidebar")
            send("astera.match.briefing.first-time.stars")
            send("astera.match.briefing.first-time.respawn")
        }
        if (norma == null) return
        send("astera.match.briefing.norma-rank", "max" to norma.maxLevel.toString())
        if (timeLimitMinutes > 0) send("astera.match.briefing.time", "minutes" to timeLimitMinutes.toString())
    }

    private companion object {
        const val DEFAULT_TIME_LIMIT_SECONDS = 600L
        const val SECONDS_PER_MINUTE = 60L
    }
}
