package com.astarworks.astera.match.domain.mode

import com.astarworks.astera.core.domain.match.TeamId

/**
 * Per-region capture progress for [CaptureWool].
 *
 * Immutable snapshot describing whether a team is currently occupying a
 * region and how many continuous ticks they have accumulated toward the
 * capture threshold. Once [capturedBy] is non-null the region is locked —
 * subsequent ticks no-op.
 *
 * `occupyingTeam == null` means contested (mixed teams in the region) or
 * empty (no one in the region). In either case progress resets.
 */
public data class RegionCaptureProgress(
    val regionId: String,
    val occupyingTeam: TeamId?,
    val accumulatedTicks: Long,
    val capturedBy: TeamId?,
) {

    /**
     * Advance progress by one scheduler period.
     *
     * Semantics:
     *  - If [capturedBy] is already set, return this unchanged (region locked).
     *  - If [occupant] is `null` (empty or contested), reset accumulated ticks
     *    to 0 and clear [occupyingTeam].
     *  - If [occupant] differs from the current [occupyingTeam], reset
     *    accumulated ticks to 1 with the new occupant (a new team starting
     *    a fresh capture attempt).
     *  - If [occupant] equals [occupyingTeam], increment accumulated ticks.
     *  - If accumulated ticks reach [ticksRequired], stamp [capturedBy] with
     *    the occupant.
     *
     * Pure function — no side effects.
     */
    public fun withTick(occupant: TeamId?, ticksRequired: Long): RegionCaptureProgress {
        if (capturedBy != null) return this
        if (occupant == null) {
            if (occupyingTeam == null && accumulatedTicks == 0L) return this
            return copy(occupyingTeam = null, accumulatedTicks = 0L)
        }
        val nextAccumulated: Long = if (occupant == occupyingTeam) accumulatedTicks + 1L else 1L
        val captured: TeamId? = if (nextAccumulated >= ticksRequired) occupant else null
        return copy(
            occupyingTeam = occupant,
            accumulatedTicks = nextAccumulated,
            capturedBy = captured,
        )
    }

    public companion object {
        public fun empty(regionId: String): RegionCaptureProgress =
            RegionCaptureProgress(
                regionId = regionId,
                occupyingTeam = null,
                accumulatedTicks = 0L,
                capturedBy = null,
            )
    }
}
