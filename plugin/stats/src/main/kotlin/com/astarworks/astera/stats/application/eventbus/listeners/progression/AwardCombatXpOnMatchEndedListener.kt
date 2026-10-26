package com.astarworks.astera.stats.application.eventbus.listeners.progression

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.progression.SkillKind
import com.astarworks.astera.stats.application.usecase.progression.AwardSkillXpUseCase

/**
 * Awards [SkillKind.Combat] XP to every player on the winning team when a
 * match ends (Wave 5multi-skill, ADR-0101).
 *
 * Why Combat (not a dedicated Tournament axis): Tournament XP is reserved
 * for Wave 7b's tournament bracket completion; per-match wins feed Combat
 * directly per ADR-0101 §"Combat (PvP DamageDealt, Match win)".
 *
 * Resolution: pulls match state from [IMatchRepository] to find the winning
 * team's roster. Match without a winner (Draw / Aborted / Reset) is a no-op.
 */
public class AwardCombatXpOnMatchEndedListener(
    private val matchRepository: IMatchRepository,
    private val awardXp: AwardSkillXpUseCase,
    private val matchWinXp: Long = MATCH_WIN_XP,
) {
    public suspend fun handle(event: MatchEnded) {
        val winning = event.winningTeam ?: return
        val match = matchRepository.findById(event.matchId) ?: return
        val winners = match.teams.firstOrNull { it.id == winning } ?: return
        winners.members.forEach { playerId ->
            awardXp.execute(playerId, SkillKind.Combat, matchWinXp)
        }
    }

    public companion object {
        public const val MATCH_WIN_XP: Long = 250L
    }
}
