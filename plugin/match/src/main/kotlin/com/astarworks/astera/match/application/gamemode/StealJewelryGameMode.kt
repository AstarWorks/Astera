package com.astarworks.astera.match.application.gamemode

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.match.domain.gamemode.GameModeContext
import com.astarworks.astera.match.domain.gamemode.GameModeEvent
import com.astarworks.astera.match.domain.gamemode.IGameMode
import com.astarworks.astera.match.domain.gamemode.IGameModePhase
import com.astarworks.astera.match.domain.mode.GameModeId

/**
 * Steal Jewelry mode — Wave 5c-N1 thin façade (ADR-0165).
 *
 * Break into the enemy vault, steal 3 jewel items, return to base. Wraps the
 * existing Wave 5c `StealJewelryListener` (subscribed to `EntityPickedUpItem`
 * with a 3-jewel counter plus `EntityEnteredRegion` for the return-zone, ADR-
 * 0032 / ADR-0159) so the [IGameMode] framework registry can advertise
 * `steal-jewelry` without re-routing theft state through this class.
 *
 * Façade contract is identical to [NormaGameMode] — see its KDoc for the
 * framework lifecycle and rationale. `onTick` is a no-op: the 3-jewel tally
 * and return detection are event-driven.
 */
public class StealJewelryGameMode @JvmOverloads constructor(
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
        // No-op: StealJewelryListener counts pickups + watches return-zone.
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
        /** Stable id; matches `content/game-modes/steal-jewelry.yaml` (Wave 7a-N1). */
        public val ID: GameModeId = GameModeId("steal-jewelry")
        public val TITLE_KEY: MessageKey = MessageKey("astera.gamemode.steal-jewelry.title")

        private val DEFAULT_PHASES: List<IGameModePhase> = listOf(
            IGameModePhase.Lobby,
            IGameModePhase.Countdown,
            IGameModePhase.Active,
            IGameModePhase.SuddenDeath,
            IGameModePhase.Ended,
        )
    }
}
