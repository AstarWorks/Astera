package com.astarworks.astera.combat.application.service

import com.astarworks.astera.core.application.port.outbound.IMatchTeamLookup
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.concurrent.ConcurrentHashMap

/**
 * Who summoned what (WP5). Host-neutral bookkeeping shared by the Summon
 * mechanic (registers / expires) and the Paper listener that keeps summons from
 * attacking their owner's side ([isFriendly]).
 *
 * Each summon remembers the owner's team at summon time; later team changes of
 * the owner are followed through [teamLookup].
 */
public class SummonRegistry(
    private val teamLookup: () -> IMatchTeamLookup?,
) {
    private val ownerBySummon: MutableMap<PlayerId, PlayerId> = ConcurrentHashMap()

    public fun register(summon: PlayerId, owner: PlayerId) {
        ownerBySummon[summon] = owner
    }

    public fun forget(summon: PlayerId) {
        ownerBySummon.remove(summon)
    }

    public fun isSummon(id: PlayerId): Boolean = id in ownerBySummon

    public fun ownerOf(summon: PlayerId): PlayerId? = ownerBySummon[summon]

    /** Summons currently alive for [owner]. */
    public fun summonsOf(owner: PlayerId): List<PlayerId> =
        ownerBySummon.entries.filter { it.value == owner }.map { it.key }

    public fun all(): List<PlayerId> = ownerBySummon.keys.toList()

    /**
     * True when [summon] must not harm or target [other]: its owner, the owner's
     * teammates, and the owner's other summons. `false` for unknown summons.
     */
    public fun isFriendly(summon: PlayerId, other: PlayerId): Boolean {
        val owner = ownerBySummon[summon] ?: return false
        if (other == owner || ownerBySummon[other] == owner) return true
        val teams = teamLookup() ?: return false
        val ownerTeam = teams.teamOf(owner) ?: return false
        val otherOwnerTeam = ownerBySummon[other]?.let { teams.teamOf(it) }
        return teams.teamOf(other) == ownerTeam || otherOwnerTeam == ownerTeam
    }
}
