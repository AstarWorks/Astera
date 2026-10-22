package com.astarworks.astera.match.application.gamemode.annihilation

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.match.domain.gamemode.GameModeContext
import com.astarworks.astera.match.domain.gamemode.GameModeEvent
import com.astarworks.astera.match.domain.gamemode.IGameMode
import com.astarworks.astera.match.domain.gamemode.IGameModePhase
import com.astarworks.astera.match.domain.gamemode.MatchHudStatus
import com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationHudStatus
import com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationConfig
import com.astarworks.astera.match.domain.mode.GameModeId

/**
 * Astera Annihilation mode — `IGameMode` impl (ADR-0108, Wave 5annihilation).
 *
 * Second `IGameMode` implementation after the Wave 5gamemode
 * [com.astarworks.astera.match.application.gamemode.NormaGameMode] façade.
 * Unlike Norma — which is a thin wrapper around the global Wave 5c pipeline
 * — Annihilation **owns** its mode-specific state machine
 * ([AnnihilationMatchTracker]) and drives it via the framework's lifecycle
 * hooks.
 *
 * ## Lifecycle wiring
 *
 *  - **onMatchStarted**: register the match with the tracker (seed pillars).
 *    Publishes the framework `GameModeStarted` event so cross-cutting
 *    consumers (HUD, audit) see the standard signal.
 *  - **onPhaseChanged**: passes through to the framework's
 *    `GameModePhaseChanged` event. The Annihilation-specific phase
 *    transitions ([com.astarworks.astera.core.domain.nexus.NexusPhase]) are
 *    driven by [AnnihilationPhaseRules] inside [onTick], not by the
 *    framework's match-state-machine phase changes.
 *  - **onTick**: advances Annihilation phases if the timer / cascade rules
 *    fire, applies sudden-death DoT in Phase 4, and evaluates the win
 *    condition. The match-end action itself (calling `IMatchLifecycle.end`)
 *    is published as a framework event and picked up by the lifecycle
 *    integration in `AsteraMatchPlugin`; we keep the win-condition
 *    decision local to the mode so it stays testable without a full
 *    lifecycle harness.
 *  - **onMatchEnded**: unregister the match from the tracker.
 *
 * ## Reflective construction
 *
 * Provides a no-arg constructor for the `GameModeContents` reflective
 * resolution path (same as `NormaGameMode`). The no-arg ctor instantiates
 * a tracker with a null bus — in this mode the lifecycle hooks are no-ops
 * until `AsteraMatchPlugin` re-registers the mode with a bus-wired
 * instance (mirrors the Norma fallback). Construction with both a tracker
 * and a bus is the path Phase C wiring takes.
 *
 * ## Why no DI for the tracker?
 *
 * Phase 1 PoC follows the established Wave 5gamemode pattern (NormaGameMode
 * holds its eventBus directly). Koin lands later; until then the manual
 * constructor injection here mirrors the rest of the plugin.
 *
 * ## Per-match config carrier
 *
 * For now [AnnihilationConfig] is fixed at construction (Phase C wires the
 * default config). Per-match overrides land when [GameModeContext.scoringState]
 * grows a typed slot — flagged in ADR-0108 §Future work.
 */
public class AnnihilationGameMode @JvmOverloads constructor(
    private val tracker: AnnihilationMatchTracker? = null,
    private val eventBus: IDomainEventBus? = null,
    private val defaultConfig: AnnihilationConfig = AnnihilationConfig.DEFAULT,
    /**
     * Current scheduler tick. `onTick` receives the absolute server tick, so the
     * tracker must stamp phase entry with the same clock (a `0` stamp would let
     * the Build phase expire at once on any server that has been up for a while).
     */
    private val clock: () -> Long = { 0L },
) : IGameMode {

    override fun id(): GameModeId = ID

    override fun titleKey(): MessageKey = TITLE_KEY

    override fun defaultPhases(): List<IGameModePhase> = DEFAULT_PHASES

    /**
     * Annihilation ticks at 20-tick cadence (1 sec). The phase rules + DoT
     * are time-based, so finer cadence would just produce idle work.
     */
    override fun tickPeriodTicks(): Long = 20L

    override fun onMatchStarted(context: GameModeContext) {
        // Seed the match-state in the tracker. Bus-less variant is a no-op
        // (the reflective fallback path) — `AsteraMatchPlugin` re-registers
        // a fully-wired instance before any production match starts.
        val stage = context.scoringState[STAGE_STATE_KEY] as? Stage
        tracker?.register(
            matchId = context.matchId,
            config = defaultConfig,
            teams = inferTeams(context),
            atTick = clock(),
            world = stage?.world,
            pillarSites = stage?.pillars.orEmpty(),
        )
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

    /**
     * Per-tick driver:
     *  1. Try to advance the Annihilation phase (timer / cascade rules).
     *  2. If currently in Phase 4 sudden-death, apply DoT to surviving
     *     Tier 3 pillars.
     *  (The win condition is decided by `AnnihilationWinConditionListener`.)
     *
     * Bus-less variant: still drives the tracker (the tracker can publish
     * events through its own bus; the mode's bus is for the framework's
     * GameMode events specifically).
     */
    override fun onTick(context: GameModeContext, tick: Long) {
        val tr = tracker ?: return
        tr.advancePhasesIfNeeded(context.matchId, tick)
        tr.applySuddenDeathTick(context.matchId, tick)
        // The win condition is not evaluated here: AnnihilationWinConditionListener
        // ends the match on TeamEliminated and on the sudden-death timer.
    }

    override fun hudStatus(match: Match, nowTick: Long): MatchHudStatus {
        val state = tracker?.state(match.id) ?: return MatchHudStatus(lines = MatchHudStatus.scoreLines(match))
        return AnnihilationHudStatus.of(state, match, nowTick)
    }

    override fun onMatchEnded(context: GameModeContext) {
        tracker?.unregister(context.matchId)
        eventBus?.publish(
            GameModeEvent.GameModeEnded(
                matchId = context.matchId,
                modeId = id(),
                atTick = 0L,
            ),
        )
    }

    /**
     * Resolve the per-team participant list from [context]. Phase 1 PoC
     * lifts teams from [context.scoringState]'s `teams` slot if the wiring
     * populated it; otherwise returns an empty list (the tracker tolerates
     * this — the resulting seed has no pillars, so `register` becomes a
     * no-op effectively. Production wiring always populates the slot.
     *
     * Reading from `scoringState` keeps the mode framework-pure — we don't
     * pull in `IMatchRepository` here just to learn the team list.
     */
    @Suppress("UNCHECKED_CAST")
    private fun inferTeams(
        context: GameModeContext,
    ): Collection<com.astarworks.astera.core.domain.match.TeamId> {
        val teamSlot = context.scoringState[TEAMS_STATE_KEY] ?: return emptyList()
        return when (teamSlot) {
            is Collection<*> -> teamSlot
                .filterIsInstance<com.astarworks.astera.core.domain.match.TeamId>()
            else -> emptyList()
        }
    }

    public companion object {
        /** Stable id used by `content/game-modes/annihilation.yaml` + queue + HUD. */
        public val ID: GameModeId = GameModeId("annihilation")
        public val TITLE_KEY: MessageKey = MessageKey("astera.gamemode.annihilation.title")

        /**
         * Key under which `GameModeContext.scoringState` carries the
         * pre-resolved team list (populated by the wiring before
         * `onMatchStarted` fires). Documented here so the wiring layer
         * has a single canonical name to write to.
         */
        public const val TEAMS_STATE_KEY: String = "annihilation.teams"

        /**
         * Key under which `GameModeContext.scoringState` carries the match's
         * [Stage] (WP10): its world and `pillars` tell the tracker where each
         * pillar stands. Absent => pillars exist in the state only (no world
         * presence, not hittable).
         */
        public const val STAGE_STATE_KEY: String = "annihilation.stage"

        private val DEFAULT_PHASES: List<IGameModePhase> = listOf(
            IGameModePhase.Lobby,
            IGameModePhase.Countdown,
            IGameModePhase.Active,
            IGameModePhase.SuddenDeath,
            IGameModePhase.Ended,
        )
    }
}
