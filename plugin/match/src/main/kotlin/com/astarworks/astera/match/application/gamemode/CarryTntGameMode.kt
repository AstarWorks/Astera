package com.astarworks.astera.match.application.gamemode

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.match.domain.gamemode.GameModeContext
import com.astarworks.astera.match.domain.gamemode.GameModeEvent
import com.astarworks.astera.match.domain.gamemode.IGameMode
import com.astarworks.astera.match.domain.gamemode.IGameModePhase
import com.astarworks.astera.match.domain.mode.GameModeId

/**
 * Carry TNT mode — Wave 5c-N1 thin façade (ADR-0165).
 *
 * Pick up a TNT entity and carry it to the enemy's delivery region.
 * Wraps the existing Wave 5c `CarryTNTListener` (subscribed to
 * `EntityPickedUpItem` + `EntityEnteredRegion`) so the [IGameMode] framework
 * registry can advertise `carry-tnt` without re-routing transport / delivery
 * detection through this class.
 *
 * Façade contract is identical to [NormaGameMode] — see its KDoc for the
 * framework lifecycle and rationale. `onTick` is a no-op: delivery is an
 * event-driven concern handled by the legacy listener.
 */
public class CarryTntGameMode @JvmOverloads constructor(
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
        // No-op: CarryTNTListener handles pickup + delivery-region transitions.
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
        /** Stable id; matches `content/game-modes/carry-tnt.yaml` (Wave 7a-N1). */
        public val ID: GameModeId = GameModeId("carry-tnt")
        public val TITLE_KEY: MessageKey = MessageKey("astera.gamemode.carry-tnt.title")

        private val DEFAULT_PHASES: List<IGameModePhase> = listOf(
            IGameModePhase.Lobby,
            IGameModePhase.Countdown,
            IGameModePhase.Active,
            IGameModePhase.SuddenDeath,
            IGameModePhase.Ended,
        )
    }
}
