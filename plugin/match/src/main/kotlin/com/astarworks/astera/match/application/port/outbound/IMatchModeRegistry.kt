package com.astarworks.astera.match.application.port.outbound

import com.astarworks.astera.core.domain.match.MatchId

/**
 * Outbound port: remembers each active match's `modeKey` string (Wave
 * 5game-complete, ADR-0121 §F).
 *
 * # Why this exists
 *
 * `Match` (in `core.domain.match`) intentionally does not carry the
 * mode-id field — `MatchStarted.modeKey` is the canonical wire-format
 * source. Several wireup-layer callers, however, need per-match mode
 * resolution **without** subscribing to `MatchStarted` themselves:
 *
 *  - `SwitchPhaseUseCase` applying `MatchMinPlayerPolicy` at
 *    COUNTDOWN→ACTIVE.
 *  - `ApplyPvpDeathPolicyListener` resolving the right
 *    `PvpDeathRule` for a kill.
 *  - `MatchEndSummaryListener` deciding which subtitle ("Victory!" vs
 *    "Defeat") to flash based on mode-specific winner semantics.
 *
 * Each subscribing to `MatchStarted` separately would duplicate the
 * cache logic three times. Centralising it in a port lets the wiring
 * register one subscriber that mirrors the modeKey into this map.
 *
 * # Lifecycle
 *
 * Entries written on `MatchStarted` (via an `AsteraMatchPlugin`
 * subscriber) and cleared on `MatchEnded`. Out-of-band callers asking
 * for a match that already ENDed return `null` — same contract as
 * `IMatchRepository.findById`.
 *
 * # Implementations
 *
 * Phase 1 PoC ships only [com.astarworks.astera.match.application.service.InMemoryMatchModeRegistry].
 * Persistence overlay is not needed: mode key is recoverable from any
 * stored MatchEnded record (Wave 5persist), and an in-memory miss on
 * server restart degrades to "treat as default mode" which the policies
 * already cover.
 */
public interface IMatchModeRegistry {

    /** Look up the mode key for [matchId], or `null` if unknown / ended. */
    public fun modeKeyOf(matchId: MatchId): String?

    /** Cache [modeKey] under [matchId]. Overwrites any prior entry. */
    public fun register(matchId: MatchId, modeKey: String?)

    /** Forget [matchId] — typically invoked on `MatchEnded`. */
    public fun unregister(matchId: MatchId)
}
