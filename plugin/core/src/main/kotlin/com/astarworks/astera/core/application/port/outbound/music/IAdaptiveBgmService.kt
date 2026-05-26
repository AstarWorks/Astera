package com.astarworks.astera.core.application.port.outbound.music

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.music.MusicMood

/**
 * Outbound port: adaptive BGM trigger orchestration (Wave 5cinematic,
 * ADR-0110).
 *
 * Wave 5pve-boss / Wave 5annihilation subscribe their HP threshold +
 * phase-transition events to this service. The service picks an appropriate
 * track for the new mood from [IMusicTrackRegistry.byMood] and asks
 * [IPerMatchBgmService] to swap.
 *
 * # Reason kinds (must match `AdaptiveMusicTriggered.ALLOWED_REASONS`)
 *
 *  - `"hp_threshold"` — boss / nexus HP dropped past a configured threshold.
 *  - `"phase_transition"` — match phase advanced.
 *  - `"player_count"` — alive-player count crossed a threshold.
 *  - `"manual"` — admin command / cutscene scripted call.
 *
 * Wave 5cinematic ships `InMemoryAdaptiveBgmService` which picks the
 * first track from `byMood(mood)` (deterministic for tests). A weighted /
 * round-robin selector is a Phase 4 follow-up.
 */
public interface IAdaptiveBgmService {

    /**
     * Trigger an adaptive mood change for [matchId]. Picks a track from the
     * registry matching [newMood], asks [IPerMatchBgmService] to apply it,
     * and publishes [com.astarworks.astera.core.domain.event.AdaptiveMusicTriggered].
     *
     * Returns Success with no-op semantics if:
     *  - No track is registered for [newMood] (logs a warning + skips —
     *    not a failure because the trigger is fire-and-forget from the
     *    subscriber's POV).
     *  - The current track already matches the selected one.
     */
    public fun trigger(
        matchId: MatchId,
        newMood: MusicMood,
        reasonKind: String,
    ): Result<Unit, AdaptiveBgmError>
}

public sealed class AdaptiveBgmError {
    public data class InvalidReason(public val detail: String) : AdaptiveBgmError()
    public data class Platform(public val detail: String) : AdaptiveBgmError()
}
