package com.astarworks.astera.match.application.gamemode

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.domain.gamemode.GameModeContext
import com.astarworks.astera.match.domain.gamemode.GameModeEvent
import com.astarworks.astera.match.domain.gamemode.HudLine
import com.astarworks.astera.match.domain.gamemode.MatchHudStatus
import com.astarworks.astera.match.domain.gamemode.IGameMode
import com.astarworks.astera.match.domain.gamemode.IGameModePhase
import com.astarworks.astera.match.domain.mode.GameModeId

/**
 * Norma mode — Phase 1 PoC's control-objective game mode (ADR-0106,
 * Wave 5gamemode).
 *
 * Wraps the existing Wave 5c pipeline (Star Generator + Anomaly schedules
 * + Sudden Death accelerator + control-region scoring) and presents it under
 * the [IGameMode] framework. **The pipeline itself is unchanged** — this
 * class is a thin façade so the framework registry can advertise "norma" as
 * an available mode and so Phase 3 Siege has a working reference impl to
 * copy from.
 *
 * Why "Norma"? `MatchmakingMode.Norma` (Wave 5w) is the queue-side name for
 * this mode; the YAML / registry naming follows. The legacy `GameMode` value
 * carriers (BattleLoyal, BreakCore, CarryTNT, …) are mode-config knobs that
 * a Norma match's stage YAML may select — they are orthogonal to the
 * framework-level "this entire match is being run in Norma mode" concept.
 *
 * ## Equivalence with Wave 5c
 *
 * The Wave 5c pipeline runs three global schedulers + one global subscriber:
 *
 *  1. `StarGeneratorScheduler` — wired in `AsteraMatchPlugin.onEnable` at
 *     the global level (one task, visits every active match's generators).
 *  2. `AnomalyScheduler` — same shape.
 *  3. `SuddenDeathAcceleratorUseCase` — subscribes to `PhaseChanged`
 *     globally; on `SUDDEN_DEATH` it overlays a MULTIPLY 0.5 modifier on
 *     every generator.
 *  4. `BreakCoreListener` — globally subscribed to BlockBroken.
 *
 * NormaGameMode **does not** rewire these. They are global and already run.
 * Its lifecycle hooks instead publish framework-level [GameModeEvent]s for
 * downstream consumers (HUD, audit, queue stats) that want a single source
 * of "the mode framework saw this match transition". The pipeline outputs
 * are unchanged; the equivalence test (`NormaGameModeEquivalenceTest`)
 * asserts that on a synthetic match-lifecycle (Started → Phase Active →
 * Phase SuddenDeath → Ended) NormaGameMode publishes the corresponding
 * framework events and otherwise leaves the world alone.
 *
 * ### Why publish framework events at all if the pipeline is global?
 *
 * Phase 3 Siege will *own* its scheduler (per-match, not global) and use
 * the same hooks to start/stop it. Phase 1 NormaGameMode being a no-op
 * keeps the contract symmetric with Phase 3 without invoking any new
 * runtime work — the events are the framework's externalised contract,
 * regardless of who owns the scheduler internally.
 *
 * ## Constructor
 *
 * Provides a no-arg constructor for reflective instantiation by
 * `GameModeContents` (per the Phase 1 YAML-driven registration scheme).
 * The optional [eventBus] parameter is for tests + Phase C wiring; when
 * null, lifecycle hooks become no-ops (the mode is still resolvable from
 * the registry but does not publish framework events). Phase C in
 * `AsteraMatchPlugin` always supplies a bus before the first match starts.
 */
public class NormaGameMode @JvmOverloads constructor(
    private val eventBus: IDomainEventBus? = null,
    /** The kill goal of a match (`score_threshold` of the mode config); the sidebar shows progress towards it. */
    private val goal: (MatchId) -> Int = { DEFAULT_GOAL },
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

    /** The norma: the team goal on top, then every team's kills towards it (`Red: 3/10`). */
    override fun hudStatus(match: Match, nowTick: Long): MatchHudStatus {
        val target = goal(match.id).toString()
        return MatchHudStatus(
            lines = buildList {
                add(HudLine(GOAL_LINE, mapOf("goal" to target)))
                match.teams.forEach { team ->
                    add(
                        HudLine(
                            key = TEAM_LINE,
                            placeholders = mapOf("score" to match.score.valueFor(team.id).toString(), "goal" to target),
                            team = team.id,
                        ),
                    )
                }
            },
        )
    }

    override fun onTick(context: GameModeContext, tick: Long) {
        // Phase 1 PoC: the Wave 5c global schedulers do all the work. The
        // tick hook exists so Phase 3 Siege has a hook point to copy.
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
        /** Stable id used by `content/game-modes/norma.yaml` + queue + HUD. */
        public val ID: GameModeId = GameModeId("norma")
        public val TITLE_KEY: MessageKey = MessageKey("astera.gamemode.norma.title")
        private val GOAL_LINE: MessageKey = MessageKey("astera.hud.sidebar.norma.goal")
        private val TEAM_LINE: MessageKey = MessageKey("astera.hud.sidebar.norma.team")

        /** Mirrors the `score_threshold` default of `content/game-modes/norma.yaml`. */
        public const val DEFAULT_GOAL: Int = 10

        private val DEFAULT_PHASES: List<IGameModePhase> = listOf(
            IGameModePhase.Lobby,
            IGameModePhase.Countdown,
            IGameModePhase.Active,
            IGameModePhase.SuddenDeath,
            IGameModePhase.Ended,
        )
    }
}
