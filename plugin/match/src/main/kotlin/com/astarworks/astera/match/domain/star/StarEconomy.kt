package com.astarworks.astera.match.domain.star

/**
 * The star economy of a match (ADR-0318), read from the `star_*` keys of `content/norma-layer/layer.yaml` (mode
 * overridable). One formula, every source:
 *
 * ```
 * ramp(t)               = piecewise-linear over match minutes (star_supply_ramp)
 * budget(t, N)          = basePerPlayerMinute x ramp(t) x N              stars per minute, N = players alive
 * source rate(t, N)     = share[source] x basePerPlayerMinute x ramp(t)  per player (or x N for shared sources)
 * ```
 *
 * Total supply therefore tracks the number of players: three players get a third of what twelve get, the per-player
 * income stays the same. A death bursts [deathGroundShare] of the victim's carried stars on the ground (the victim keeps the rest);
 * a kill also pays a bounty out of the budget.
 */
public data class StarEconomy(
    val basePerPlayerMinute: Double = DEFAULT_BASE,
    val ramp: List<RampPoint> = DEFAULT_RAMP,
    val shares: Map<StarSource, Double> = DEFAULT_SHARES,
    val miningStars: Int = 1,
    val blockStars: Int = 1,
    val blockMaterials: Set<String> = emptySet(),
    /** Share of a victim's carried stars that bursts on the ground at death; the victim keeps the rest. */
    val deathGroundShare: Double = 0.5,
    /** Bounty = rate x (victim carried - match average carried), clamped to [bountyCap]. */
    val bountyRate: Double = 0.5,
    val bountyCap: Int = 12,
) {
    public data class RampPoint(val minute: Double, val multiplier: Double)

    init {
        require(basePerPlayerMinute > 0.0) { "star_supply_per_player_minute must be > 0 (was $basePerPlayerMinute)" }
        require(ramp.isNotEmpty() && ramp.zipWithNext().all { (a, b) -> a.minute < b.minute }) { "star_supply_ramp needs points with increasing minutes" }
        require(ramp.all { it.multiplier >= 0.0 }) { "star_supply_ramp multipliers must be >= 0" }
        require(StarSource.entries.all { shares[it]?.let { s -> s in 0.0..1.0 } == true }) { "a star_share_* key is missing or outside 0..1: $shares" }
        require(shares.values.sum() <= 1.0 + 1e-9) { "star_share_* must add up to at most 1 (was ${shares.values.sum()})" }
        require((shares[StarSource.SPOT] ?: 0.0) <= MAX_SPOT_SHARE) { "star_share_spot is a minor source: at most $MAX_SPOT_SHARE" }
        require(miningStars >= 1 && blockStars >= 1) { "star_mining_per_node / star_block_per_break must be >= 1" }
        require(deathGroundShare in 0.0..1.0) { "star_death_ground_share must stay within 0..1" }
        require(bountyRate >= 0.0 && bountyCap >= 0) { "star_bounty_rate / star_bounty_cap must be >= 0" }
    }

    /** Supply multiplier [seconds] into the match. */
    public fun rampAt(seconds: Double): Double {
        val minute = seconds / 60.0
        if (minute <= ramp.first().minute) return ramp.first().multiplier
        if (minute >= ramp.last().minute) return ramp.last().multiplier
        val hi = ramp.indexOfFirst { it.minute >= minute }
        val a = ramp[hi - 1]
        val b = ramp[hi]
        return a.multiplier + (b.multiplier - a.multiplier) * (minute - a.minute) / (b.minute - a.minute)
    }

    /** Stars per player per minute the match issues [seconds] in. */
    public fun perPlayerMinuteAt(seconds: Double): Double = basePerPlayerMinute * rampAt(seconds)

    /** The whole match's budget in stars per minute for [players] alive. */
    public fun budgetPerMinute(seconds: Double, players: Int): Double = perPlayerMinuteAt(seconds) * players

    /**
     * Refill rate of a [source] bucket in stars per second ([players] only matters for shared sources).
     * [openAt] is the match second each source opens at (phase-gated sources, 0 = from the start): a source is 0 before it
     * opens, and while some are closed their shares move to the open ones (every open cap is raised by
     * `all shares / open shares`, the timer generators never above [MAX_SPOT_SHARE]), so the caps still add up to the whole budget.
     */
    public fun sourceRatePerSecond(source: StarSource, seconds: Double, players: Int, openAt: (StarSource) -> Double = { 0.0 }): Double =
        (effectiveShare(source, seconds, openAt) * perPlayerMinuteAt(seconds) * (if (source.perPlayer) 1 else players)) / 60.0

    /** The [shares] entry of [source] at [seconds] once the shares of closed sources ([openAt]) have moved to the open ones. */
    public fun effectiveShare(source: StarSource, seconds: Double, openAt: (StarSource) -> Double = { 0.0 }): Double {
        if (seconds < openAt(source)) return 0.0
        val open = StarSource.entries.filter { seconds >= openAt(it) }
        val openShare = open.sumOf { shares.getValue(it) }
        if (openShare <= 0.0) return 0.0
        val pool = StarSource.entries.sumOf { shares.getValue(it) }
        val boost = pool / openShare
        val spotShare = shares.getValue(StarSource.SPOT)
        if (StarSource.SPOT !in open || spotShare * boost <= MAX_SPOT_SHARE) return shares.getValue(source) * boost
        // The minor source stays at its limit; everything else shares what is left.
        if (source == StarSource.SPOT) return MAX_SPOT_SHARE
        return shares.getValue(source) * (pool - MAX_SPOT_SHARE) / (openShare - spotShare)
    }

    public companion object {
        public const val MAX_SPOT_SHARE: Double = 0.15
        public const val DEFAULT_BASE: Double = 16.0
        public val DEFAULT_RAMP: List<RampPoint> = listOf(RampPoint(0.0, 0.25), RampPoint(1.0, 0.75), RampPoint(3.0, 1.0), RampPoint(11.0, 1.4), RampPoint(16.0, 1.6))
        public val DEFAULT_SHARES: Map<StarSource, Double> = mapOf(
            StarSource.SPOT to 0.12, StarSource.MINING to 0.22, StarSource.BLOCKS to 0.15,
            StarSource.CACHE to 0.15, StarSource.WAVE to 0.30, StarSource.BOUNTY to 0.04,
        )

        /** Reads the `star_*` keys of the merged layer + mode config; a missing key keeps its default, a malformed one fails loudly. */
        public fun parse(raw: Map<String, String>): StarEconomy {
            val d = StarEconomy()
            fun number(key: String, fallback: Double): Double =
                raw[key]?.trim()?.let { it.toDoubleOrNull() ?: throw IllegalArgumentException("$key must be a number (was '$it')") } ?: fallback
            val ramp = raw["star_supply_ramp"]?.let { text ->
                text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { pair ->
                    val (m, v) = pair.split(':').also { require(it.size == 2) { "star_supply_ramp entries are minute:multiplier (was '$pair')" } }
                    RampPoint(
                        m.trim().toDoubleOrNull() ?: throw IllegalArgumentException("star_supply_ramp minute must be a number (was '$m')"),
                        v.trim().toDoubleOrNull() ?: throw IllegalArgumentException("star_supply_ramp multiplier must be a number (was '$v')"),
                    )
                }
            } ?: d.ramp
            val shares = StarSource.entries.associateWith { number("star_share_${it.key}", d.shares.getValue(it)) }
            return StarEconomy(
                basePerPlayerMinute = number("star_supply_per_player_minute", d.basePerPlayerMinute),
                ramp = ramp,
                shares = shares,
                miningStars = number("star_mining_per_node", d.miningStars.toDouble()).toInt(),
                blockStars = number("star_block_per_break", d.blockStars.toDouble()).toInt(),
                blockMaterials = raw["star_blocks"]?.split(',')?.map { it.trim().uppercase() }?.filter { it.isNotEmpty() }?.toSet() ?: d.blockMaterials,
                deathGroundShare = number("star_death_ground_share", d.deathGroundShare),
                bountyRate = number("star_bounty_rate", d.bountyRate),
                bountyCap = number("star_bounty_cap", d.bountyCap.toDouble()).toInt(),
            )
        }
    }
}
