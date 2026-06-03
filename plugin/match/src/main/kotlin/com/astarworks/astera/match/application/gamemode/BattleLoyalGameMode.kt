package com.astarworks.astera.match.application.gamemode

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.match.domain.gamemode.GameModeContext
import com.astarworks.astera.match.domain.gamemode.GameModeEvent
import com.astarworks.astera.match.domain.gamemode.IGameMode
import com.astarworks.astera.match.domain.gamemode.IGameModePhase
import com.astarworks.astera.match.domain.mode.GameModeId

/**
 * Battle Loyal mode — Wave 5c-N1 thin façade (ADR-0165).
 *
 * Small-scale arena: first team to N points wins. Wraps the existing Wave 5c
 * pipeline (`BattleLoyalListener` + `VictoryConditionEvaluator` score-threshold
 * path, ADR-0027 / ADR-0032) so the [IGameMode] framework registry can advertise
 * `battle-loyal` to HUD / queue UI / `/astera-match start` tab-completion without
 * forcing those surfaces to know the legacy listener exists.
 *
 * Façade contract is identical to [NormaGameMode] — see its KDoc for the
 * framework lifecycle and "why publish framework events" reasoning. As
 * [com.astarworks.astera.match.domain.mode.BattleLoyal] KDoc notes, the mode
 * "needs no dedicated tick loop", so `onTick` is a clean no-op here.
 */
public class BattleLoyalGameMode @JvmOverloads constructor(
    private val eventBus: IDomainEventBus? = null,
) : IGameMode {

    override fun id(): GameModeId = ID

    override fun titleKey(): MessageKey = TITLE_KEY

    override fun defaultPhases(): List<IGameModePhase> = DEFAULT_PHASES

    override fun tickPeriodTicks(): Long = 20L

    override fun onMatchStarted(context: GameModeContext) {
        eventBus?.publish(
            GameModeEvent.GameModeStarted(
                matchId = context.matchId,
                modeId = id(),
                atTick = 0L,
            ),
        )
    }

    override fun onPhaseChanged(context: GameModeContext, oldPhase: IGameModePhase, newPhase: IGameModePhase) {
        if (oldPhase == newPhase) return
        eventBus?.publish(
            GameModeEvent.GameModePhaseChanged(
                matchId = context.matchId,
                modeId = id(),
                oldPhase = oldPhase,
                newPhase = newPhase,
                atTick = 0L,
            ),
        )
    }

    override fun onTick(context: GameModeContext, tick: Long) {
        // No-op: Wave 5c global pipeline + BattleLoyalListener handle scoring.
    }

    override fun onMatchEnded(context: GameModeContext) {
        eventBus?.publish(
            GameModeEvent.GameModeEnded(
                matchId = context.matchId,
                modeId = id(),
                atTick = 0L,
            ),
        )
    }

    public companion object {
        /** Stable id; matches `content/game-modes/battle-loyal.yaml` (Wave 7a-N1). */
        public val ID: GameModeId = GameModeId("battle-loyal")
        public val TITLE_KEY: MessageKey = MessageKey("astera.gamemode.battle-loyal.title")

        private val DEFAULT_PHASES: List<IGameModePhase> = listOf(
            IGameModePhase.Lobby,
            IGameModePhase.Countdown,
            IGameModePhase.Active,
            IGameModePhase.SuddenDeath,
            IGameModePhase.Ended,
        )
    }
}
