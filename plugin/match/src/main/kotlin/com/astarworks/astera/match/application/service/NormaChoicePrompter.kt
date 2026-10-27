package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runLaterTicks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.gui.match.NormaChoiceGuiSpec
import com.astarworks.astera.match.application.port.outbound.INormaPresentation
import com.astarworks.astera.match.domain.norma.NormaConfig
import com.astarworks.astera.match.domain.norma.NormaError
import com.astarworks.astera.match.domain.norma.NormaGoal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The Norma goal (STAR or KILL) defaults to STAR and is never forced on the player: nothing opens over the screen at
 * match start. At each level-up [hint] shows a one-line, non-blocking action-bar note with the current goal and how to
 * change it (`/astera norma choose`). [prompt] opens the picker GUI (auto-closing after [WINDOW_TICKS]) when a caller
 * explicitly wants it; its clicks land in [handle].
 */
public class NormaChoicePrompter(
    private val matches: IMatchRepository,
    private val norma: NormaLevelService,
    private val configOf: (MatchId) -> NormaConfig,
    private val guiAdapter: IGuiAdapter,
    private val mcServer: IMcServer,
    private val scheduler: IScheduler,
    private val messages: (IMcPlayer, MessageKey, Map<String, String>) -> Unit,
    private val presentation: INormaPresentation? = null,
    /** Shows a short non-blocking line (action bar) with placeholders. */
    private val hintSink: (PlayerId, MessageKey, Map<String, String>) -> Unit = { _, _, _ -> },
) {
    /** Open picker per player: the token of the prompt that opened it (a stale timeout must not announce a newer prompt). */
    private val pending: MutableMap<PlayerId, Long> = ConcurrentHashMap()
    private val tokens = AtomicLong()

    /** Non-blocking level-up note: the current goal stands, and `/astera norma choose` changes it. */
    public fun hint(playerId: PlayerId) {
        val hud = norma.hudOf(playerId) ?: return
        if (hud.level >= hud.maxLevel) return
        val key = if (hud.goal == NormaGoal.STAR) "astera.match.hint.norma-choice-star" else "astera.match.hint.norma-choice-kill"
        hintSink(playerId, MessageKey(key), mapOf("target" to hud.target.toString()))
    }

    /** Opens the picker for [playerId] if they are in a live Norma match below the top level. */
    public fun prompt(playerId: PlayerId) {
        val hud = norma.hudOf(playerId) ?: return
        if (hud.level >= hud.maxLevel) return
        val match = matches.findMatchOf(playerId) ?: return
        val player = mcServer.findPlayer(playerId) ?: return
        val cfg = configOf(match.id)
        val spec = NormaChoiceGuiSpec.build(
            hud.level, hud.maxLevel, cfg.starsGoalAt(hud.level), cfg.killsGoalAt(hud.level),
            starsCarried = hud.stars, kills = norma.killsAtLevelOf(playerId),
        )
        if (!guiAdapter.open(player, spec)) return
        val token = tokens.incrementAndGet()
        pending[playerId] = token
        presentation?.choiceOpened(playerId)
        scheduler.runLaterTicks(ExecutionScope.Entity(playerId.uuid), WINDOW_TICKS) {
            val p = mcServer.findPlayer(playerId) ?: return@runLaterTicks
            if (guiAdapter.currentSpec(p)?.id == NormaChoiceGuiSpec.ID) guiAdapter.close(p)
            if (pending.remove(playerId, token)) announceDefault(playerId)
        }
    }

    /** Nobody picked in time: tell the player the default goal stands. */
    private fun announceDefault(playerId: PlayerId) {
        val hud = norma.hudOf(playerId) ?: return
        presentation?.choiceDefaulted(playerId, hud.goal, hud.target)
    }

    public fun handle(intent: GuiIntent, player: IMcPlayer) {
        if (intent.key != NormaChoiceGuiSpec.INTENT_CHOOSE) return
        val goal = if (intent.payload[NormaChoiceGuiSpec.PAYLOAD_GOAL].equals("kill", ignoreCase = true)) NormaGoal.KILL else NormaGoal.STAR
        pending.remove(player.id)
        when (val res = norma.choose(player.id, goal)) {
            is Result.Success -> {
                norma.hudOf(player.id)?.let { presentation?.choicePicked(player.id, goal, it.target) }
                messages(player, MessageKey("astera.match.norma-choice.chosen"), mapOf("goal" to goal.name.lowercase()))
            }
            is Result.Failure -> messages(player, errorKey(res.error), emptyMap())
        }
        guiAdapter.close(player)
    }

    public companion object {
        /** The message that explains [error] accurately. */
        public fun errorKey(error: NormaError): MessageKey = MessageKey(
            when (error) {
                NormaError.NotInMatch -> "astera.match.norma-choice.not-in-match"
                NormaError.ModeHasNoNorma -> "astera.match.norma-choice.no-norma-mode"
                NormaError.MatchNotRunning -> "astera.match.norma-choice.match-not-running"
                NormaError.AtMaxLevel -> "astera.match.norma-choice.max-level"
            },
        )

        public const val INTENT_PREFIX: String = "match.norma."
        public const val WINDOW_TICKS: Long = 200L
    }
}
