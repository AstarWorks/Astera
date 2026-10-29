package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.domain.norma.NormaCheckError
import com.astarworks.astera.match.domain.norma.NormaError
import com.astarworks.astera.match.domain.norma.NormaGoal

/**
 * The Norma goal (STAR or KILL) defaults to STAR and is never forced on the player: nothing opens over the screen at
 * match start. At each level-up [hint] shows a one-line, non-blocking action-bar note with the current goal and how to
 * change it (`/astera norma choose`).
 */
public class NormaChoicePrompter(
    private val norma: NormaLevelService,
    /** Shows a short non-blocking line (action bar) with placeholders. */
    private val hintSink: (PlayerId, MessageKey, Map<String, String>) -> Unit = { _, _, _ -> },
) {
    /** Non-blocking level-up note: the current goal stands, and `/astera norma choose` changes it. */
    public fun hint(playerId: PlayerId) {
        val hud = norma.hudOf(playerId) ?: return
        if (hud.level >= hud.maxLevel) return
        val key = if (hud.goal == NormaGoal.STAR) "astera.match.hint.norma-choice-star" else "astera.match.hint.norma-choice-kill"
        hintSink(playerId, MessageKey(key), mapOf("target" to hud.target.toString()))
    }

    public companion object {
        /** The message that explains the refused Norma check [error]; [NormaCheckError.NotReady] also needs `{missing}` / `{goal}`. */
        public fun checkErrorKey(error: NormaCheckError): MessageKey = MessageKey(
            when (error) {
                NormaCheckError.NotInMatch -> "astera.match.norma-choice.not-in-match"
                NormaCheckError.ModeHasNoNorma -> "astera.match.norma-choice.no-norma-mode"
                NormaCheckError.MatchNotRunning -> "astera.match.norma-choice.match-not-running"
                NormaCheckError.AtMaxLevel -> "astera.match.norma-choice.max-level"
                NormaCheckError.NotAtBase -> "astera.match.norma-check.not-at-base"
                NormaCheckError.EnemyBase -> "astera.match.norma-check.enemy-base"
                is NormaCheckError.NotReady -> "astera.match.norma-check.not-ready-${error.goal.name.lowercase()}"
            },
        )

        /** The message that explains [error] accurately. */
        public fun errorKey(error: NormaError): MessageKey = MessageKey(
            when (error) {
                NormaError.NotInMatch -> "astera.match.norma-choice.not-in-match"
                NormaError.ModeHasNoNorma -> "astera.match.norma-choice.no-norma-mode"
                NormaError.MatchNotRunning -> "astera.match.norma-choice.match-not-running"
                NormaError.AtMaxLevel -> "astera.match.norma-choice.max-level"
            },
        )
    }
}
