package com.astarworks.astera.match.domain.gamemode

import com.astarworks.astera.core.domain.match.MatchPhase as CoreMatchPhase

/**
 * Framework-level phase classification (ADR-0106, Wave 5gamemode).
 *
 * Decouples the framework from the concrete `enum MatchPhase` (Core) so a
 * future game mode can introduce an extra phase (e.g. `Negotiation` for a
 * trade-RP mode) without forcing an enum change in Core. The framework
 * translates between [IGameModePhase] and [CoreMatchPhase] at the edges via
 * [fromCore] / [toCore].
 *
 * The four shipped variants cover every state the Wave 5c PoC pipeline can
 * reach. Custom phases land in Phase 3+ as additional sealed subclasses
 * (e.g. `data class Custom(val key: String)`); the conversion helpers
 * `fromCore` / `toCore` will keep mapping the four canonical ones and grow
 * a `Custom` round-trip when needed.
 *
 * Pure data; no behaviour. Modes use [IGameMode.defaultPhases] to advertise
 * their advertised layout for HUD rendering.
 */
public sealed interface IGameModePhase {

    /** Pre-match. Players in lobby, mode not started yet. */
    public data object Lobby : IGameModePhase

    /** Pre-active countdown (Wave 5c uses this for spawn-protection windows). */
    public data object Countdown : IGameModePhase

    /** Main play phase. Mode-owned schedulers run here. */
    public data object Active : IGameModePhase

    /** Final stretch — mode may overlay accelerators (NormaGameMode does 0.5× period mult). */
    public data object SuddenDeath : IGameModePhase

    /** Concluded. No further onTick fires; onMatchEnded already ran. */
    public data object Ended : IGameModePhase

    public companion object {
        /**
         * Convert from Core's concrete `enum MatchPhase` to the framework's
         * sealed equivalent. Total — every Core variant maps to a single
         * `IGameModePhase`.
         */
        public fun fromCore(phase: CoreMatchPhase): IGameModePhase =
            when (phase) {
                CoreMatchPhase.WAITING -> Lobby
                CoreMatchPhase.COUNTDOWN -> Countdown
                CoreMatchPhase.ACTIVE -> Active
                CoreMatchPhase.SUDDEN_DEATH -> SuddenDeath
                CoreMatchPhase.ENDED -> Ended
            }

        /**
         * Convert back to Core's enum. Partial — sealed `Custom` variants
         * (Phase 3+) will return `null` and the framework will log + skip the
         * transition for legacy `MatchPhase`-bound subscribers.
         */
        public fun toCore(phase: IGameModePhase): CoreMatchPhase? =
            when (phase) {
                Lobby -> CoreMatchPhase.WAITING
                Countdown -> CoreMatchPhase.COUNTDOWN
                Active -> CoreMatchPhase.ACTIVE
                SuddenDeath -> CoreMatchPhase.SUDDEN_DEATH
                Ended -> CoreMatchPhase.ENDED
            }
    }
}
