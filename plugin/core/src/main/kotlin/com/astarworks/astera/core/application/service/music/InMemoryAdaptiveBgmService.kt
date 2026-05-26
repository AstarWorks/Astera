package com.astarworks.astera.core.application.service.music

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.music.AdaptiveBgmError
import com.astarworks.astera.core.application.port.outbound.music.IAdaptiveBgmService
import com.astarworks.astera.core.application.port.outbound.music.IMusicTrackRegistry
import com.astarworks.astera.core.application.port.outbound.music.IPerMatchBgmService
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.AdaptiveMusicTriggered
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.music.MusicMood

/**
 * Default in-memory [IAdaptiveBgmService] (Wave 5cinematic, ADR-0110).
 *
 * **Selection strategy**: picks the first track from
 * `IMusicTrackRegistry.byMood(mood)` — deterministic + test-friendly. A
 * weighted / round-robin selector is a Phase 4 follow-up.
 *
 * # Reason kind validation
 *
 * Validates against [AdaptiveMusicTriggered.ALLOWED_REASONS] before any
 * side effect. An invalid reason is a contract violation by the caller
 * (the trigger is fire-and-forget from the subscriber's POV, but the
 * reason is captured for audit so we surface bad reasons explicitly).
 */
public class InMemoryAdaptiveBgmService(
    private val tracks: IMusicTrackRegistry,
    private val perMatch: IPerMatchBgmService,
    private val eventBus: IDomainEventBus,
    private val tickClock: () -> Long = { 0L },
) : IAdaptiveBgmService {

    override fun trigger(
        matchId: MatchId,
        newMood: MusicMood,
        reasonKind: String,
    ): Result<Unit, AdaptiveBgmError> {
        if (reasonKind !in AdaptiveMusicTriggered.ALLOWED_REASONS) {
            return Result.failure(
                AdaptiveBgmError.InvalidReason(
                    "reasonKind must be one of ${AdaptiveMusicTriggered.ALLOWED_REASONS} (got '$reasonKind')",
                ),
            )
        }
        val candidates = tracks.byMood(newMood)
        if (candidates.isEmpty()) {
            // No tracks for this mood — log + skip is the right behaviour per
            // port KDoc. No event fired (nothing actually changed).
            return Result.success(Unit)
        }
        val selected = candidates.first()
        eventBus.publish(
            AdaptiveMusicTriggered(
                matchId = matchId,
                newMood = newMood,
                selectedTrack = selected.id,
                reasonKind = reasonKind,
                atTick = tickClock(),
            ),
        )
        // Forward to the per-match service. If that fails, it's a platform
        // issue we surface verbatim — the caller is fire-and-forget but the
        // audit chain wants to see the failure.
        return when (val r = perMatch.setTrack(matchId, selected.id)) {
            is Result.Success -> Result.success(Unit)
            is Result.Failure -> Result.failure(
                AdaptiveBgmError.Platform("per-match service rejected: ${r.error}"),
            )
        }
    }
}
