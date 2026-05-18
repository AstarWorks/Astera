package com.astarworks.astera.match.domain.mode

import com.astarworks.astera.core.domain.match.MatchId

/**
 * Holds the runtime association of a match → mode + mode-specific state.
 *
 * Immutable; [withState] returns a copy with one key replaced. The state
 * map is intentionally a flat `Map<String, String>` for Phase 1 PoC — it
 * is just enough to plumb mode-driven values (e.g. "current capture
 * progress", "carrying TNT charge") through without committing to a
 * stronger shape yet. Wave 6 may replace it with a typed state hierarchy
 * once usage patterns stabilize.
 */
public data class GameModeContext(
    val matchId: MatchId,
    val mode: GameMode,
    val modeState: Map<String, String> = emptyMap(),
) {

    /** Returns a copy of this context with [key] → [value] in [modeState]. */
    public fun withState(key: String, value: String): GameModeContext =
        copy(modeState = modeState + (key to value))
}
