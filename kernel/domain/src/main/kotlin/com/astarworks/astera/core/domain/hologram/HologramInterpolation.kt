package com.astarworks.astera.core.domain.hologram

/**
 * Smooth-animation settings for a Display entity (Wave 5h, ADR-0079 §1).
 *
 * Mirrors Bukkit `Display.interpolationDuration` /
 * `Display.interpolationDelay` / `Display.teleportDuration`. When
 * `durationTicks > 0`, an `updateTransform` produces a client-side
 * interpolated tween over that many ticks instead of an instant snap.
 *
 * - [durationTicks]: ticks the transform tween runs for after start
 * - [delayTicks]: ticks to wait before the tween starts (useful for
 *   chained animations)
 * - [teleportDurationTicks]: ticks the position-only tween runs for on
 *   `teleport`. Bukkit caps this at 59 in practice; we don't enforce here
 *   to keep the domain clamp-free — clamping is the adapter's job.
 *
 * Defaults are all zero (no interpolation, snap on every update) so callers
 * who don't care about animation don't have to think about it.
 */
public data class HologramInterpolation(
    public val durationTicks: Int = 0,
    public val delayTicks: Int = 0,
    public val teleportDurationTicks: Int = 0,
) {
    init {
        require(durationTicks >= 0) { "durationTicks must be >= 0, got $durationTicks" }
        require(delayTicks >= 0) { "delayTicks must be >= 0, got $delayTicks" }
        require(teleportDurationTicks >= 0) {
            "teleportDurationTicks must be >= 0, got $teleportDurationTicks"
        }
    }
}
