package com.astarworks.astera.match.domain.star

/**
 * Where stars come from (ADR-0318). Every source draws against the match budget and has its own cap, a token bucket
 * that refills at `share x budget` ([perPlayer] sources: one bucket per player, so the cap is per player per minute;
 * shared sources: one bucket per match that scales with the number of players). [burstMinutes] is how many minutes
 * of refill a bucket can hold, i.e. the largest single grant.
 */
public enum class StarSource(public val key: String, public val perPlayer: Boolean, public val burstMinutes: Double) {
    /** Timer generators (flank / base star spots): a minor source. */
    SPOT("spot", perPlayer = false, burstMinutes = 2.0),

    /** Mining a Mid ore node. */
    MINING("mining", perPlayer = true, burstMinutes = 1.0),

    /** Breaking a block of the content-listed set. */
    BLOCKS("blocks", perPlayer = true, burstMinutes = 1.0),

    /** Opening an exploration cache. */
    CACHE("cache", perPlayer = true, burstMinutes = 2.0),

    /** Mid contested waves. */
    WAVE("wave", perPlayer = false, burstMinutes = 2.0),

    /** Kill bounty on a leader. */
    BOUNTY("bounty", perPlayer = false, burstMinutes = 3.0),
    ;

    /** Id of the pseudo generator the supply events of this source carry. */
    public val generatorId: String get() = "star-source-$key"
}
