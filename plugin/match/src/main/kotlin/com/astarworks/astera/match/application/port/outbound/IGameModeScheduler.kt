package com.astarworks.astera.match.application.port.outbound

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.domain.gamemode.GameModeContext
import com.astarworks.astera.match.domain.gamemode.IGameMode

/**
 * Outbound port: drives [IGameMode.onTick] at each mode's declared cadence
 * (ADR-0106, Wave 5gamemode).
 *
 * Default implementation:
 * [com.astarworks.astera.match.application.service.DefaultGameModeScheduler].
 * Wraps Core's [IScheduler] — one repeating task per active match per mode
 * (or fewer, if modes return `0L` for [IGameMode.tickPeriodTicks]).
 *
 * ## Lifecycle integration
 *
 * The scheduler is owned by `AsteraMatchPlugin` and subscribes implicitly to:
 *
 *  - `MatchStarted` → [register] the match with its resolved mode
 *  - phase transitions → forwarded to the mode via [IGameMode.onPhaseChanged]
 *    (the scheduler itself only schedules + cancels; the framework owns
 *    invocation order)
 *  - `MatchEnded` → [unregister] the match (cancels the tick task)
 *
 * Test fakes implement this interface to assert the framework registered /
 * unregistered the correct match-mode pair without actually scheduling
 * anything on a real scheduler. See `FakeGameModeScheduler` in
 * `plugin/match/src/test/.../testfixtures/`.
 *
 * Single-threaded contract: all methods invoked from the Paper main thread.
 */
public interface IGameModeScheduler {

    /**
     * Begin ticking [mode] for [matchId]. Idempotent — re-registering an
     * existing pair cancels the prior task and re-schedules. If
     * [IGameMode.tickPeriodTicks] returns `0L` the implementation MUST NOT
     * schedule a task; the registration is still tracked so [unregister]
     * succeeds.
     *
     * @return handle that the framework retains so [unregister] can be skipped
     *   in error paths (the handle's cancel is idempotent). For Phase 1 PoC
     *   the framework always pairs register + unregister explicitly; the
     *   handle is exposed for future hot-swap scenarios.
     */
    public fun register(matchId: MatchId, mode: IGameMode, context: GameModeContext): Registration

    /**
     * Stop ticking [matchId]. Cancels any scheduled task and clears the
     * tracked context. No-op if [matchId] was not registered.
     */
    public fun unregister(matchId: MatchId)

    /**
     * Snapshot of currently-registered match ids. Used by tests and the
     * `/astera-match list` HUD (Phase 3+); not by the framework's hot path.
     */
    public fun activeMatches(): Set<MatchId>

    /** Handle returned by [register]; call [cancel] to detach. Idempotent. */
    public interface Registration {
        public fun cancel()
    }
}
