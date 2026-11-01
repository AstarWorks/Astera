package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.domain.star.StarSource
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The star budget of every running match (ADR-0318): `budget(t, N) = per_player_minute(t) x N_alive`. Every source asks
 * [grant] before it issues stars. A grant has to fit twice: the source's own token bucket (its cap, refilling at
 * `share x budget`, per player for per-player sources) and the match-wide bucket (the budget itself). Buckets start
 * empty at match start (a timer-generator lane starts with one opening star outside the match-wide bucket) and hold at most [StarSource.burstMinutes] of refill, so over any span the issued total cannot exceed the
 * budget integral. Requests that do not fit are cut down or refused and counted as cap hits ([stats]).
 *
 * A source can be gated ([openAt]: the match second it opens at, 0 = always open; Annihilation's Mid wave opens at P3). A gated
 * source has no bucket before it opens, its share of the budget moves to the open sources (their caps are raised, see
 * [com.astarworks.astera.match.domain.star.StarEconomy.sourceRatePerSecond]), and it starts with a full bucket when it opens.
 */
public class StarBudget(
    private val clock: StarSupplyClock,
    private val openAt: (MatchId, StarSource) -> Double = { _, _ -> 0.0 },
    private val aliveOf: (MatchId) -> Int,
) {
    private class Bucket(var tokens: Double = 0.0, var atSeconds: Double = 0.0)

    private data class Key(val matchId: MatchId, val source: StarSource?, val player: PlayerId?, val lane: String? = null)

    private val buckets = ConcurrentHashMap<Key, Bucket>()
    private val openingUsed = ConcurrentHashMap.newKeySet<Key>()

    /** Counters per match for the sim and diagnostics. */
    public val stats: Stats = Stats()

    /** Players the budget scales with right now (alive, at least 1). */
    public fun alive(matchId: MatchId): Int = aliveOf(matchId).coerceAtLeast(1)

    /**
     * Stars [source] may issue to [player] ([player] is ignored for shared sources): the granted part of [requested], 0..requested.
     * A source split into [lanes] equal lanes (the timer generators, so one generator cannot starve the others) passes its [lane].
     */
    @Synchronized
    public fun grant(matchId: MatchId, source: StarSource, player: PlayerId?, requested: Int, lane: String? = null, lanes: Int = 1): Int {
        if (requested <= 0) return 0
        val economy = clock.economyOf(matchId)
        val now = clock.elapsedSeconds(matchId)
        val n = alive(matchId)
        val opens = StarSource.entries.associateWith { openAt(matchId, it) }
        val since = opens.getValue(source)
        if (now < since) {
            stats.add(matchId, "requested:${source.key}", requested)
            stats.add(matchId, "caphit:${source.key}", 1)
            return 0
        }
        val own = refill(Key(matchId, source, player.takeIf { source.perPlayer }, lane), now, since) { t -> economy.sourceRatePerSecond(source, t, n) { opens.getValue(it) } / lanes.coerceAtLeast(1) to source.burstMinutes }
        val global = refill(Key(matchId, null, null), now, 0.0) { t -> economy.budgetPerMinute(t, n) / 60.0 to GLOBAL_BURST_MINUTES }
        // The opening star of a timer-generator lane is a one-off outside the match-wide bucket (at most one per lane, ~7 stars a match),
        // so it neither waits for the 0.25-ramp budget nor takes tokens from the other sources.
        val opening = lane != null && openingUsed.add(Key(matchId, source, null, lane)) && own.tokens >= 1.0
        val granted = if (opening) 1 else minOf(requested, own.tokens.toInt(), global.tokens.toInt())
        own.tokens -= granted
        if (!opening) global.tokens -= granted
        stats.add(matchId, "requested:${source.key}", requested)
        stats.add(matchId, "issued:${source.key}", granted)
        if (granted < requested) stats.add(matchId, "caphit:${source.key}", 1)
        return granted
    }

    private fun refill(key: Key, now: Double, since: Double, rateAndBurst: (Double) -> Pair<Double, Double>): Bucket {
        // A lane (one timer generator) starts with one star in hand, so every generator can make its first pop on its first
        // period instead of waiting minutes for a 0.25-ramp share to fill (3 players: ~3 min per lane).
        val bucket = buckets.computeIfAbsent(key) { Bucket(if (key.lane != null) 1.0 else 0.0, 0.0) }
        val (rateNow, burst) = rateAndBurst(now)
        if (bucket.atSeconds < since) {
            // The source was closed until now: it opens with a full bucket (the first wave is sized for a whole period).
            bucket.tokens = maxOf(rateNow * 60.0 * burst, 1.0)
            bucket.atSeconds = now
            return bucket
        }
        val (rateThen, _) = rateAndBurst(bucket.atSeconds)
        val dt = (now - bucket.atSeconds).coerceAtLeast(0.0)
        bucket.tokens = minOf(maxOf(rateNow * 60.0 * burst, 1.0), bucket.tokens + (rateThen + rateNow) / 2.0 * dt)
        bucket.atSeconds = now
        return bucket
    }

    /** Forget a finished match. */
    public fun clear(matchId: MatchId) {
        buckets.keys.removeIf { it.matchId == matchId }
        openingUsed.removeIf { it.matchId == matchId }
        stats.clear(matchId)
    }

    public class Stats {
        private val counters = ConcurrentHashMap<Pair<MatchId, String>, AtomicLong>()

        public fun add(matchId: MatchId, name: String, amount: Int) {
            counters.computeIfAbsent(matchId to name) { AtomicLong() }.addAndGet(amount.toLong())
        }

        public fun get(matchId: MatchId, name: String): Long = counters[matchId to name]?.get() ?: 0L

        public fun issued(matchId: MatchId, source: StarSource): Long = get(matchId, "issued:${source.key}")

        public fun capHits(matchId: MatchId, source: StarSource): Long = get(matchId, "caphit:${source.key}")

        internal fun clear(matchId: MatchId) {
            counters.keys.removeIf { it.first == matchId }
        }
    }

    private companion object {
        /** The match-wide bucket holds this many minutes of budget. */
        const val GLOBAL_BURST_MINUTES = 3.0
    }
}
