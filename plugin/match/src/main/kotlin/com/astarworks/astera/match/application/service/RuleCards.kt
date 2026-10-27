package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.gamemode.siege.CountRules
import java.util.concurrent.ConcurrentHashMap

/**
 * The three-line rule card of a match mode: the goal, how to win, one tip (`astera.rules.<mode>.goal|how|tip`).
 * Shown when a match turns ACTIVE, on demand with `/astera rules`, and once more on a beginner's first death in a match.
 */
public class RuleCards(
    private val matches: IMatchRepository,
    private val players: IPlayerGateway,
    private val messages: IMessageRenderer,
    private val modeKeyOf: (MatchId) -> String?,
    private val configOf: (MatchId) -> Map<String, String>,
    /** True while the player has never finished a match (only they get the death reminder). */
    private val isFirstMatch: (PlayerId) -> Boolean = { false },
) {
    private val diedOnce: MutableSet<Pair<MatchId, PlayerId>> = ConcurrentHashMap.newKeySet()

    /** Writes the card of [matchId]'s mode to [viewer]'s chat. False when the mode has no card. */
    public fun send(viewer: PlayerId, matchId: MatchId): Boolean {
        val mode = modeKeyOf(matchId)?.takeIf { it.isNotBlank() } ?: return false
        val params = CountRules.briefingParams(configOf(matchId))
        val lines = LINES.map { messages.render(viewer, MessageKey("astera.rules.$mode.$it"), params) }
        if (lines.any { it.isBlank() || it.startsWith("astera.") }) return false
        players.sendMessage(viewer, messages.render(viewer, HEADER))
        lines.forEach { players.sendMessage(viewer, it) }
        return true
    }

    /** `/astera rules`: the card of the match [viewer] is in. False outside a match. */
    public fun sendCurrent(viewer: PlayerId): Boolean {
        val match = matches.findMatchOf(viewer) ?: return false
        return send(viewer, match.id)
    }

    /** A death in a match: a beginner sees the card once per match. */
    public fun onDeath(victim: PlayerId) {
        val match = matches.findMatchOf(victim) ?: return
        if (!isFirstMatch(victim) || !diedOnce.add(match.id to victim)) return
        send(victim, match.id)
    }

    /** Forgets the first-death bookkeeping of [matchId]. */
    public fun onMatchEnded(matchId: MatchId) {
        diedOnce.removeIf { it.first == matchId }
    }

    private companion object {
        val HEADER = MessageKey("astera.rules.header")
        val LINES = listOf("goal", "how", "tip")
    }
}
