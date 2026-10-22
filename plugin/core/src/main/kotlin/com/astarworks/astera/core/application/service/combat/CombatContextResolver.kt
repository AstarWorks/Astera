package com.astarworks.astera.core.application.service.combat

import com.astarworks.astera.core.application.port.outbound.IMatchTeamLookup
import com.astarworks.astera.core.domain.combat.CombatContext
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Decides the [CombatContext] of an event at its source (ADR-0237). The lookup is resolved per call:
 * the match plugin registers [IMatchTeamLookup] after combat enables.
 */
public class CombatContextResolver(private val lookup: () -> IMatchTeamLookup?) {

    /**
     * Damage / death of a player: a match context only when the victim is in a match and the
     * attacker (when it is another player) is in a match too. An unattributed hit (fall, DoT without
     * an origin) on a match participant belongs to that match.
     */
    public fun betweenPlayers(attacker: PlayerId?, victim: PlayerId): CombatContext {
        val teams = lookup() ?: return CombatContext.Lobby
        if (teams.teamOf(victim) == null) return CombatContext.Lobby
        if (attacker != null && attacker != victim && teams.teamOf(attacker) == null) return CombatContext.Lobby
        return CombatContext.Match(teams.matchOf(victim))
    }

    /** Damage / death of a non-player entity (mob, structure): a match context only through a participating [attacker]. */
    public fun againstEntity(attacker: PlayerId?): CombatContext {
        val teams = lookup() ?: return CombatContext.World
        if (attacker == null || teams.teamOf(attacker) == null) return CombatContext.World
        return CombatContext.Match(teams.matchOf(attacker))
    }
}
