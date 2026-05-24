package com.astarworks.astera.match.domain.gamemode

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.domain.mode.GameModeId

/**
 * Astera game-mode framework — behavioural interface (ADR-0106, Wave 5gamemode).
 *
 * An `IGameMode` is the *operating manual* for one variant of "what is a match".
 * The Phase 1 PoC ships exactly one implementation ([NormaGameMode], wrapping
 * the Wave 5c control-objective pipeline). The framework exists so Phase 3+
 * additions (Siege, Deathmatch, CTF, Battle Royale, Co-op PvE) can be added by
 * dropping a new `IGameMode` class + YAML — no rewiring of `AsteraMatchPlugin`.
 *
 * ## Relationship with the legacy `GameMode` (data class hierarchy)
 *
 * `com.astarworks.astera.match.domain.mode.GameMode` (sealed) is **config**:
 * a value carrying mode-specific knobs (BattleLoyal.scoreThreshold,
 * BreakCore.coreBlockId, …). It pre-dates this framework and stays as-is —
 * the orchestration listeners under `match.application.usecase.mode/` still
 * consume those values.
 *
 * `IGameMode` (this file) is **behaviour**: the lifecycle contract that the
 * framework calls into. A given `IGameMode` impl may *delegate* to one or
 * more legacy `GameMode` configs (NormaGameMode wires the Star Generator +
 * Anomaly + Sudden Death pipeline that targets a `BattleLoyal`-style
 * objective), or own its own per-impl config (Phase 3 Siege).
 *
 * The two coexist because:
 *  - the legacy hierarchy ships 6 working mode-configs that already run in
 *    the PoC — destructive collapse would break the Wave 5c demo,
 *  - per the Phase 1 pre-release posture, we *prefer* destructive change
 *    where it cleans up design — but here the legacy values *are* the design
 *    for per-mode tunables and the framework simply adds the missing
 *    "what does the match actually do" axis on top.
 *
 * Once Phase 3 Siege lands (Wave 8-y.2+), the legacy `GameMode` sealed class
 * is expected to be retired or absorbed into per-`IGameMode` config types;
 * that decision is intentionally deferred until two non-Norma implementations
 * exist to inform the right shape.
 *
 * ## Lifecycle contract
 *
 * The framework calls hooks in this order:
 *
 *   1. [onMatchStarted] — fired from a `MatchStarted` subscription. Mode
 *      seeds per-match state (region progress maps, spawn counters, etc.).
 *   2. [onPhaseChanged] — fired on each [com.astarworks.astera.core.domain.match.MatchPhase]
 *      transition observed via the central event bus. Modes may schedule or
 *      cancel mode-specific schedulers (Star Generator advance, Anomaly draws,
 *      …) here.
 *   3. [onTick] — fired by [IGameModeScheduler] at the mode's declared cadence.
 *      Pure / side-effect-free *from the framework's perspective*: the mode
 *      may publish domain events but must not mutate match state directly.
 *   4. [onMatchEnded] — fired from a `MatchEnded` subscription. Mode tears
 *      down any owned schedulers and persists any required end-of-match state.
 *
 * All hooks are *advisory* — the framework swallows exceptions thrown by an
 * implementation, logs them via the orchestrator, and continues. A mode that
 * crashes during `onTick` does not bring down the match (per ADR-0001's
 * Minecraft-edge isolation principle).
 *
 * ## Threading
 *
 * All hooks run on the Paper main thread. Implementations MUST NOT block the
 * thread or perform I/O; offload to the scheduler if needed (see
 * `match.application.usecase.match.PhaseTimerUseCase` for the canonical
 * pattern).
 *
 * ## Why not just events?
 *
 * The framework could be expressed purely as event subscriptions, and indeed
 * the Phase 1 wiring still uses [IDomainEventBus][com.astarworks.astera.core.application.port.outbound.IDomainEventBus]
 * underneath. The explicit `IGameMode` interface buys three things events
 * alone don't:
 *
 *  - a single registration handle (the registry) for "which modes are
 *    available" — useful for `/astera-match start` tab-completion and YAML
 *    validation,
 *  - a stable per-mode config carrier ([defaultPhases], [tickPeriodTicks]),
 *  - a place to hang Phase 3 additions (scoring, win condition, reward
 *    distribution policy) without inventing a new event variant per concern.
 */
public interface IGameMode {

    /** Stable identifier; matches the YAML file's `id:` and the registry key. */
    public fun id(): GameModeId

    /** i18n key for the player-facing mode title (HUD, queue UI). */
    public fun titleKey(): MessageKey

    /**
     * Phase layout this mode advertises. The match state machine still owns
     * actual phase transitions (Wave 4.5 `MatchStateMachine`); this list is
     * advisory metadata that surface tools (lobby HUD, scoreboard) read to
     * render mode-aware UI without parsing the implementation class.
     *
     * Phase 1 PoC: NormaGameMode returns `[Lobby, Active, SuddenDeath, Ended]`
     * (the only phases the Wave 5c pipeline supports today).
     */
    public fun defaultPhases(): List<IGameModePhase>

    /**
     * How often [onTick] is invoked, in Minecraft ticks (20 ticks = 1s).
     *
     * Defaults to `20L` (once per second). Modes that need finer cadence
     * (Battle Royale shrinking zones) override; modes that don't need a tick
     * loop at all return `0L` and the scheduler skips them.
     */
    public fun tickPeriodTicks(): Long = 20L

    /**
     * Fired immediately after `MatchStarted` is published on the event bus.
     * Mode seeds per-match in-memory state via [context] mutators.
     */
    public fun onMatchStarted(context: GameModeContext)

    /**
     * Fired on every observed phase transition for this mode's match.
     * `oldPhase` may equal `newPhase` only on initial seed (defensive); modes
     * should compare and no-op in that case.
     */
    public fun onPhaseChanged(context: GameModeContext, oldPhase: IGameModePhase, newPhase: IGameModePhase)

    /**
     * Fired by [IGameModeScheduler] at [tickPeriodTicks] cadence while the
     * match is in any non-`Ended` phase. `tick` is the absolute server tick
     * (Paper `Bukkit.getCurrentTick()` equivalent) so modes that need
     * monotonic time can rely on it directly.
     */
    public fun onTick(context: GameModeContext, tick: Long)

    /**
     * Fired immediately after `MatchEnded` is published. Mode tears down
     * any owned schedulers, persists end-state, etc.
     */
    public fun onMatchEnded(context: GameModeContext)
}

/**
 * Trivial accessor mirror of [IGameMode.id] for places where pulling the
 * whole interface in is overkill (e.g. registry-key lookup).
 */
@Suppress("unused")
public fun IGameMode.matchModeKey(@Suppress("UNUSED_PARAMETER") matchId: MatchId): GameModeId = id()
