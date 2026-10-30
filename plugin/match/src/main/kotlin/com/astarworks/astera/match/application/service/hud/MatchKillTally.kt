package com.astarworks.astera.match.application.service.hud

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-match kills / deaths shown on the match sidebar. In memory, keyed by match instance, dropped with
 * [clear] when the match ends; never persisted. Attribution mirrors the kill feed: a killer other than the
 * victim gets a kill, the victim always gets a death (suicide / environment = death only).
 */
public class MatchKillTally {

    private class Counts {
        val kills = AtomicInteger()
        val deaths = AtomicInteger()
    }

    private val byMatch = ConcurrentHashMap<MatchId, ConcurrentHashMap<PlayerId, Counts>>()

    public fun record(matchId: MatchId, victim: PlayerId, killer: PlayerId?) {
        val counts = byMatch.computeIfAbsent(matchId) { ConcurrentHashMap() }
        counts.computeIfAbsent(victim) { Counts() }.deaths.incrementAndGet()
        if (killer != null && killer != victim) counts.computeIfAbsent(killer) { Counts() }.kills.incrementAndGet()
    }

    /** `kills to deaths` of [player] in this match; `0 to 0` before the first event. */
    public fun of(matchId: MatchId, player: PlayerId): Pair<Int, Int> {
        val c = byMatch[matchId]?.get(player) ?: return 0 to 0
        return c.kills.get() to c.deaths.get()
    }

    public fun clear(matchId: MatchId) {
        byMatch.remove(matchId)
    }
}
