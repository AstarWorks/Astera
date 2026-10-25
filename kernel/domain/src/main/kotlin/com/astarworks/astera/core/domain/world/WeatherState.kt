package com.astarworks.astera.core.domain.world

/**
 * Vendor-neutral weather state vocabulary.
 *
 * The Paper adapter (Wave 6b) maps these to `World.setStorm` / `setThundering`
 * / `setWeatherDuration` / `setThunderDuration`. The domain stays free of any
 * Bukkit type so use cases can be tested with the in-memory gateway.
 *
 * Wave 5n (ADR-0080): introduced alongside [IWorldWeatherGateway] for
 * per-match weather lock and Wave 5c anomaly support.
 */
public sealed interface WeatherState {

    /** Sunny, no rain, no thunder. */
    public data object Clear : WeatherState

    /**
     * Rain (no thunder) for [durationTicks] server ticks. Caller is
     * responsible for choosing a sensible duration; a value of `0` means
     * "indefinite" (the world stays raining until next set).
     */
    public data class Rain(val durationTicks: Int) : WeatherState {
        init {
            require(durationTicks >= 0) { "durationTicks must be >= 0" }
        }
    }

    /**
     * Thunderstorm (rain + thunder) for [durationTicks] server ticks. A
     * value of `0` means "indefinite".
     */
    public data class Thunder(val durationTicks: Int) : WeatherState {
        init {
            require(durationTicks >= 0) { "durationTicks must be >= 0" }
        }
    }
}
