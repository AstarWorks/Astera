package com.astarworks.astera.match.application.eventbus.listeners.match

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runLaterTicks
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.domain.event.PhaseChanged
import com.astarworks.astera.match.domain.norma.NormaConfig

/**
 * "How to win" briefing, staged so a player takes in one thing at a time (BX-03 / SP-05).
 *
 * When a match turns ACTIVE (after the countdown) each participant gets, in order:
 *  1. t=0: the objective as a title (`astera.match.objective.<modeKey>.title|subtitle`, one sentence) plus the mode's
 *     chat line `astera.match.briefing.mode.<modeKey>`. The chat is only a backup of the title.
 *  2. From [FIRST_HINT_DELAY_TICKS] on, one short action-bar hint every [HINT_SPACING_TICKS] (`astera.match.hint.*`),
 *     each mirrored by its longer chat line: the beginner guide (first match only), then what Norma levels unlock (they never decide
 *     the match, ADR-0288), then the time-limit rule. A hint is dropped if the match is no longer ACTIVE.
 *
 * The beginner guide is keyed on the stats' matches-played counter, so it shows once.
 */
public class MatchBriefingListener(
    private val matches: IMatchRepository,
    private val playerGateway: IPlayerGateway,
    private val messages: IMessageRenderer,
    private val scheduler: IScheduler,
    private val modeKeyOf: (MatchId) -> String?,
    private val configOf: (MatchId) -> Map<String, String>,
    private val isNorma: (MatchId) -> Boolean,
    /** Shows a title + subtitle (already resolved against the viewer's language by the Paper layer). */
    private val showTitle: (PlayerId, MessageKey, MessageKey) -> Unit = { _, _, _ -> },
    /** Shows a short one-line hint (action bar) with placeholders. */
    private val showHint: (PlayerId, MessageKey, Map<String, String>) -> Unit = { _, _, _ -> },
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
        for (member in members) brief(event.matchId, member, modeKey, norma, timeLimitMinutes)
    }

    private fun brief(matchId: MatchId, viewer: PlayerId, modeKey: String?, norma: NormaConfig?, timeLimitMinutes: Long) {
        fun send(key: String, params: Map<String, String> = emptyMap()) =
            playerGateway.sendMessage(viewer, messages.render(viewer, MessageKey(key), params))

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

        // Later stages, one at a time: a short action-bar hint plus its longer chat lines.
        val stages = buildList {
            if (isFirstMatch(viewer)) {
                add(Stage("astera.match.hint.sidebar", emptyMap(), listOf("astera.match.briefing.first-time.header", "astera.match.briefing.first-time.sidebar")))
                add(Stage("astera.match.hint.stars", emptyMap(), listOf("astera.match.briefing.first-time.stars")))
                add(Stage("astera.match.hint.respawn", emptyMap(), listOf("astera.match.briefing.first-time.respawn")))
            }
            if (norma != null) {
                val max = mapOf("max" to norma.maxLevel.toString())
                add(Stage("astera.match.hint.norma-rank", max, listOf("astera.match.briefing.norma-rank")))
                if (timeLimitMinutes > 0) {
                    add(Stage("astera.match.hint.time", mapOf("minutes" to timeLimitMinutes.toString()), listOf("astera.match.briefing.time")))
                }
            }
        }
        stages.forEachIndexed { index, stage ->
            val delay = FIRST_HINT_DELAY_TICKS + index * HINT_SPACING_TICKS
            scheduler.runLaterTicks(ExecutionScope.Entity(viewer.uuid), delay) {
                if (matches.findById(matchId)?.phase != MatchPhase.ACTIVE) return@runLaterTicks
                showHint(viewer, MessageKey(stage.hintKey), stage.params)
                for (chat in stage.chatKeys) send(chat, stage.params)
            }
        }
    }

    private class Stage(val hintKey: String, val params: Map<String, String>, val chatKeys: List<String>)

    public companion object {
        /** First hint, after the objective title has faded. */
        public const val FIRST_HINT_DELAY_TICKS: Long = 200L

        /** Gap between two hints. */
        public const val HINT_SPACING_TICKS: Long = 260L

        private const val DEFAULT_TIME_LIMIT_SECONDS = 600L
        private const val SECONDS_PER_MINUTE = 60L
    }
}
