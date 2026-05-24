package com.astarworks.astera.match.domain.gamemode

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.domain.mode.GameModeId

/**
 * Per-match context handed to every [IGameMode] lifecycle hook
 * (ADR-0106, Wave 5gamemode).
 *
 * Carries the match id + current mode + participant snapshot + per-mode
 * scratch state (typed `Map<String, Any>` for Phase 1 PoC). Immutable —
 * [withState] / [withParticipants] return copies. Concurrent readers always
 * see a self-consistent snapshot because the `IGameModeScheduler` runs on
 * the Paper main thread and rebuilds the context per tick.
 *
 * ### Why not collapse with `match.domain.mode.GameModeContext`?
 *
 * The legacy `match.domain.mode.GameModeContext` (Wave 5c) wraps a
 * sealed `GameMode` (config) and a flat `Map<String, String>` and is read
 * by the per-mode-config listeners (`BreakCoreListener`, etc.). That class
 * stays; this is the *framework-level* context handed to the
 * behaviour-side `IGameMode` impl. Two contexts because:
 *
 *  - the legacy one couples 1:1 to the legacy `GameMode` config object,
 *  - the framework one couples to a participant + scoring view that the
 *    legacy listeners never needed.
 *
 * Per the Phase 3 plan ([[adr/0106]] §Open questions), the two contexts
 * may unify once Siege lands and informs the merged shape. Until then they
 * coexist and each owns its narrow concern.
 *
 * @property matchId The match this context is bound to.
 * @property modeId The mode id resolved from the registry. Carried so hooks
 *   that fan out to multiple modes don't need to thread it separately.
 * @property participants Snapshot of player ids that joined the match. Modes
 *   that need team affinity should resolve it via `IMatchRepository.findById`
 *   on the fly — keeping this list flat avoids re-encoding `Team` here.
 * @property scoringState Per-mode scratch map. Typed `Any` for Phase 1 PoC
 *   so modes can store rich values (e.g. `RegionCaptureProgress`) without a
 *   forced `String` round-trip. A typed scoring registry lands in Phase 3.
 */
public data class GameModeContext(
    val matchId: MatchId,
    val modeId: GameModeId,
    val participants: List<PlayerId> = emptyList(),
    val scoringState: Map<String, Any> = emptyMap(),
) {

    /** Returns a copy of this context with [key] → [value] in [scoringState]. */
    public fun withState(key: String, value: Any): GameModeContext =
        copy(scoringState = scoringState + (key to value))

    /** Returns a copy of this context with the participant list replaced. */
    public fun withParticipants(participants: List<PlayerId>): GameModeContext =
        copy(participants = participants)

    /**
     * Read typed value from [scoringState]. Returns `null` if missing or if
     * the stored value's runtime type does not match [T]. Callers should
     * treat a `null` as "no scratch state set yet" rather than crash.
     */
    public inline fun <reified T : Any> readState(key: String): T? =
        scoringState[key] as? T
}
