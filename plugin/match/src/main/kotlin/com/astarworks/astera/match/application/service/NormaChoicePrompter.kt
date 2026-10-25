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
import com.astarworks.astera.match.domain.norma.NormaConfig
import com.astarworks.astera.match.domain.norma.NormaGoal

/**
 * Asks a player for the Norma goal (STAR or KILL) without a command: a small GUI opens at match start and at each
 * level-up and closes by itself after [WINDOW_TICKS] (10 s). Not choosing leaves the default, STAR. The GUI clicks
 * land in [handle]; `/astera norma choose` stays available.
 */
public class NormaChoicePrompter(
    private val matches: IMatchRepository,
    private val norma: NormaLevelService,
    private val configOf: (MatchId) -> NormaConfig,
    private val guiAdapter: IGuiAdapter,
    private val mcServer: IMcServer,
    private val scheduler: IScheduler,
    private val messages: (IMcPlayer, MessageKey, Map<String, String>) -> Unit,
) {
    /** Opens the picker for [playerId] if they are in a live Norma match below the top level. */
    public fun prompt(playerId: PlayerId) {
        val hud = norma.hudOf(playerId) ?: return
        if (hud.level >= hud.maxLevel) return
        val match = matches.findMatchOf(playerId) ?: return
        val player = mcServer.findPlayer(playerId) ?: return
        val cfg = configOf(match.id)
        val spec = NormaChoiceGuiSpec.build(hud.level, hud.maxLevel, cfg.starsGoalAt(hud.level), cfg.killsGoalAt(hud.level))
        if (!guiAdapter.open(player, spec)) return
        scheduler.runLaterTicks(ExecutionScope.Entity(playerId.uuid), WINDOW_TICKS) {
            val p = mcServer.findPlayer(playerId) ?: return@runLaterTicks
            if (guiAdapter.currentSpec(p)?.id == NormaChoiceGuiSpec.ID) guiAdapter.close(p)
        }
    }

    public fun handle(intent: GuiIntent, player: IMcPlayer) {
        if (intent.key != NormaChoiceGuiSpec.INTENT_CHOOSE) return
        val goal = if (intent.payload[NormaChoiceGuiSpec.PAYLOAD_GOAL].equals("kill", ignoreCase = true)) NormaGoal.KILL else NormaGoal.STAR
        when (norma.choose(player.id, goal)) {
            is Result.Success -> messages(player, MessageKey("astera.match.norma-choice.chosen"), mapOf("goal" to goal.name.lowercase()))
            is Result.Failure -> messages(player, MessageKey("astera.match.norma-choice.not-in-match"), emptyMap())
        }
        guiAdapter.close(player)
    }

    public companion object {
        public const val INTENT_PREFIX: String = "match.norma."
        public const val WINDOW_TICKS: Long = 200L
    }
}
