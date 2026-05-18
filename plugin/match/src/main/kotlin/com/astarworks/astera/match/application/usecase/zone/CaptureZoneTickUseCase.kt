package com.astarworks.astera.match.application.usecase.zone

import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.match.domain.zone.CaptureZone

/**
 * Advance a single [CaptureZone] one tick given current occupancy (Wave 5d).
 *
 * Inputs:
 * - [zone] — the current immutable state
 * - [occupantsByTeam] — `Map<TeamId, Int>` of players inside the region,
 *   grouped by team. Counts of `0` and absent keys are treated identically
 *   (the use case never reads the count value, only the set of teams with
 *   `> 0` occupants — quantity is held in the API for Wave 7+ weighting).
 *
 * # Rules
 *
 * - **Empty or contested** (zero teams or ≥ 2 teams present): no change.
 * - **Single-team uncontested**:
 *   - `currentOwner == thatTeam` → progress capped at
 *     [CaptureZone.Companion.MAX_PROGRESS]; no other change.
 *   - `currentOwner == null` → progress increments by
 *     [CaptureZone.captureRatePerTick]. Once it hits
 *     [CaptureZone.Companion.MAX_PROGRESS] ownership transfers to that team
 *     (progress stays at MAX).
 *   - `currentOwner == otherTeam` → progress decrements by
 *     [CaptureZone.captureRatePerTick]. Once it reaches 0 ownership clears
 *     (becomes neutral); the same tick does **not** start filling for the
 *     new team — the next tick begins the takeover.
 *
 * Pure function — no side effects, no I/O, no events emitted (the scheduler
 * compares old vs new and publishes the diff).
 */
public class CaptureZoneTickUseCase {

    public fun tick(
        zone: CaptureZone,
        occupantsByTeam: Map<TeamId, Int>,
    ): CaptureZone {
        // Filter to teams with strictly-positive occupancy. The map values
        // are reserved for future weighting but ignored in Phase 1.
        val occupyingTeams = occupantsByTeam
            .asSequence()
            .filter { (_, count) -> count > 0 }
            .map { it.key }
            .toSet()

        // Empty or contested → no change.
        if (occupyingTeams.size != 1) return zone

        val team = occupyingTeams.single()
        val rate = zone.captureRatePerTick
        val max = CaptureZone.MAX_PROGRESS

        return when (zone.currentOwner) {
            team -> {
                // Already-owning team rests on / tops off the meter.
                if (zone.progress >= max) zone
                else zone.copy(progress = max)
            }
            null -> {
                // Neutral zone being captured by the only present team.
                val next = (zone.progress + rate).coerceAtMost(max)
                if (next >= max) {
                    zone.copy(progress = max, currentOwner = team)
                } else {
                    zone.copy(progress = next)
                }
            }
            else -> {
                // Opposing team draining the current owner's meter.
                val next = (zone.progress - rate).coerceAtLeast(0)
                if (next == 0) {
                    // Neutralized — next scheduler tick begins takeover for [team].
                    zone.copy(progress = 0, currentOwner = null)
                } else {
                    zone.copy(progress = next)
                }
            }
        }
    }
}
