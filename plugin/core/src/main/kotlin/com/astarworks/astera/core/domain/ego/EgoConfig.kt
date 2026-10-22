package com.astarworks.astera.core.domain.ego

/**
 * Tuning of the per-player E.G.O gauge (ADR-0231), loaded from
 * `content/balance/ego.yaml`.
 *
 * The gauge runs `0..[max]`. It fills by [gainOnDealt] for every hit the
 * player lands on another player or mob and by [gainOnTaken] for every hit the
 * player takes, and drains by [decayPerSecond] once the player has been out of
 * combat for [decayDelayTicks]. Skills spend it with `ego_cost`.
 */
public data class EgoConfig(
    val max: Int = 100,
    val gainOnDealt: Int = 5,
    val gainOnTaken: Int = 4,
    val decayPerSecond: Int = 2,
    val decayDelayTicks: Int = 200,
) {
    init {
        require(max > 0) { "max must be > 0, got $max" }
        require(gainOnDealt >= 0) { "gainOnDealt must be >= 0, got $gainOnDealt" }
        require(gainOnTaken >= 0) { "gainOnTaken must be >= 0, got $gainOnTaken" }
        require(decayPerSecond >= 0) { "decayPerSecond must be >= 0, got $decayPerSecond" }
        require(decayDelayTicks >= 0) { "decayDelayTicks must be >= 0, got $decayDelayTicks" }
    }

    public companion object {
        public val DEFAULTS: EgoConfig = EgoConfig()
    }
}
