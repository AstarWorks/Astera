package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.world.WorldId

/**
 * Outbound port: control the in-game time-of-day of a Bukkit world.
 *
 * Phrased entirely in domain types so use cases (per-match config overlay,
 * anomaly time-warp variant, /astera time admin command) stay testable.
 *
 * The Paper adapter (Wave 6b) implements this against `World.setTime` /
 * `World.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, ...)`. Until then the in-
 * memory implementation tracks state via a `MutableMap<WorldId, Long>` so
 * unit tests can assert the use-case-side calls.
 *
 * **Tick convention**: Minecraft's `timeOfDay` is in the range `0..24000`,
 * where `0 = dawn`, `6000 = noon`, `12000 = dusk`, `18000 = midnight`. The
 * gateway accepts values in `[0, 24000)`; out-of-range values are clamped /
 * modulo'd by the Paper-side impl (vanilla behaviour).
 *
 * Wave 5n (ADR-0092).
 */
public interface IWorldTimeGateway {

    /**
     * Set the time-of-day of [world] to [timeOfDay] ticks (mod 24000). Has
     * no effect if the world is unknown.
     */
    public fun setTime(world: WorldId, timeOfDay: Long)

    /**
     * Freeze (or unfreeze) the day/night cycle for [world]. When frozen,
     * vanilla time advancement stops; calling [setTime] still works.
     *
     * Equivalent to the `doDaylightCycle` GameRule but exposed here for
     * the per-match config overlay's convenience (config has `timeMode:
     * Long?` which implies both set + freeze).
     */
    public fun freezeTime(world: WorldId, frozen: Boolean)

    /**
     * Returns the current time-of-day of [world] (`0..23999`), or `null` if
     * the world is unknown.
     */
    public fun getCurrentTime(world: WorldId): Long?

    /**
     * Returns `true` if time is currently frozen for [world]. Returns
     * `false` when the world is unknown (callers needing to distinguish
     * "unknown" should call [getCurrentTime] first).
     */
    public fun isTimeFrozen(world: WorldId): Boolean
}
