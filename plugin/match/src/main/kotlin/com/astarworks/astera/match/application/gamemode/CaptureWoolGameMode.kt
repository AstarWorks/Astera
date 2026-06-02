package com.astarworks.astera.match.application.gamemode

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.match.domain.gamemode.GameModeContext
import com.astarworks.astera.match.domain.gamemode.GameModeEvent
import com.astarworks.astera.match.domain.gamemode.IGameMode
import com.astarworks.astera.match.domain.gamemode.IGameModePhase
import com.astarworks.astera.match.domain.mode.GameModeId

/**
 * Capture Wool mode — Wave 5c-N1 thin façade (ADR-0165).
 *
 * Region-control variant: hold the wool flag inside enemy-controlled zones to
 * accumulate occupancy time. Wraps the existing Wave 5c `CaptureWoolListener`
 * (subscribed to `EntityEnteredRegion` / `EntityLeftRegion` for the
 * `RegionCaptureProgress` state, ADR-0032 / ADR-0159) so the [IGameMode]
 * framework registry can advertise `capture-wool` without re-routing region
 * timers through this class.
 *
 * Façade contract is identical to [NormaGameMode] — see its KDoc for the
 * framework lifecycle and rationale. `onTick` is a no-op: occupancy
 * accumulation lives event-side on the legacy listener.
 */
public class CaptureWoolGameMode @JvmOverloads constructor(
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
        // No-op: CaptureWoolListener consumes region-entry events globally.
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
        /** Stable id; matches `content/game-modes/capture-wool.yaml` (Wave 7a-N1). */
        public val ID: GameModeId = GameModeId("capture-wool")
        public val TITLE_KEY: MessageKey = MessageKey("astera.gamemode.capture-wool.title")

        private val DEFAULT_PHASES: List<IGameModePhase> = listOf(
            IGameModePhase.Lobby,
            IGameModePhase.Countdown,
            IGameModePhase.Active,
            IGameModePhase.SuddenDeath,
            IGameModePhase.Ended,
        )
    }
}
