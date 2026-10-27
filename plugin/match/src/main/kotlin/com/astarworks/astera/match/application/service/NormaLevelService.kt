package com.astarworks.astera.match.application.service

import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.application.port.outbound.IMatchNormaStore
import com.astarworks.astera.match.application.port.outbound.IMatchStarStore
import com.astarworks.astera.match.domain.event.NormaLevelUp
import com.astarworks.astera.match.domain.event.StarsCollected
import com.astarworks.astera.match.domain.norma.NormaConfig
import com.astarworks.astera.match.domain.norma.NormaError
import com.astarworks.astera.match.domain.norma.NormaGoal
import com.astarworks.astera.match.domain.norma.NormaHud
import com.astarworks.astera.match.domain.norma.NormaProgress
import com.astarworks.astera.match.domain.norma.NormaStanding

/**
 * Orange-Juice-style Norma levels, per (match, player). Everyone starts at level 1 with the STAR goal. At each level
 * the player holds one goal: carry `norma_stars_goal` stars (not consumed) or make `norma_kills_goal` kills at this
 * level. Reaching it levels the player up at once (kills reset, stars kept, the choice re-opens at STAR). The first
 * team with a member at `norma_max_level` wins the match when `norma_win` is on (the default), alongside the mode's
 * own objective, whichever happens first. Everything is dropped at match end.
 */
public class NormaLevelService(
    private val matches: IMatchRepository,
    private val stars: IMatchStarStore,
    private val norma: IMatchNormaStore,
    private val configOf: (MatchId) -> NormaConfig,
    private val isNorma: (MatchId) -> Boolean,
    private val eventBus: IDomainEventBus,
    private val lifecycle: IMatchLifecycle,
) {
    public fun onKilled(event: PlayerKilled) {
        val killer = event.killer ?: return
        val match = matches.findMatchOf(event.victim) ?: return
        if (!live(match) || !isNorma(match.id)) return
        val killerTeam = match.teams.firstOrNull { killer in it.members }?.id ?: return
        val victimTeam = match.teams.firstOrNull { event.victim in it.members }?.id ?: return
        if (killerTeam == victimTeam) return
        // The kill is tallied (last time-limit tie-break) before it can level the killer up and end the match.
        // `Match.score` is left to the mode's own objective (the siege modes' deliveries).
        val p = norma.get(match.id, killer)
        norma.put(match.id, killer, p.copy(killsAtLevel = p.killsAtLevel + 1, totalKills = p.totalKills + 1))
        evaluate(match, killer)
    }

    public fun onStarsCollected(event: StarsCollected) {
        val match = matches.findById(event.matchId) ?: return
        if (!live(match) || !isNorma(match.id)) return
        evaluate(match, event.playerId)
    }

    /** `/astera norma choose star|kill`. */
    public fun choose(playerId: PlayerId, goal: NormaGoal): Result<NormaProgress, NormaError> {
        val match = matches.findMatchOf(playerId) ?: return Result.failure(NormaError.NotInMatch)
        if (!isNorma(match.id)) return Result.failure(NormaError.ModeHasNoNorma)
        // The picker opens right after the match starts, which is the COUNTDOWN phase: choosing then is allowed.
        if (match.phase == MatchPhase.WAITING || match.phase == MatchPhase.ENDED) return Result.failure(NormaError.MatchNotRunning)
        val p = norma.get(match.id, playerId)
        if (p.level >= configOf(match.id).maxLevel) return Result.failure(NormaError.AtMaxLevel)
        val updated = p.copy(goal = goal)
        norma.put(match.id, playerId, updated)
        if (live(match)) evaluate(match, playerId)
        return Result.success(norma.get(match.id, playerId))
    }

    /** The sidebar block of [playerId], or `null` outside a live Norma match. */
    public fun hudOf(playerId: PlayerId): NormaHud? {
        val match = matches.findMatchOf(playerId)?.takeIf { isNorma(it.id) } ?: return null
        val cfg = configOf(match.id)
        val p = norma.get(match.id, playerId)
        val carried = stars.get(match.id, playerId)
        val target = cfg.targetFor(p.level, p.goal)
        val progress = if (p.goal == NormaGoal.STAR) carried else p.killsAtLevel
        val rival = match.teams.filter { playerId !in it.members }.maxOfOrNull { topLevel(match, it.id) }
        return NormaHud(carried, p.level, cfg.maxLevel, p.goal, progress.coerceAtMost(target), target, rival)
    }

    /** Kills [playerId] made since reaching the current level (0 outside a Norma match). */
    public fun killsAtLevelOf(playerId: PlayerId): Int {
        val match = matches.findMatchOf(playerId)?.takeIf { isNorma(it.id) } ?: return 0
        return norma.get(match.id, playerId).killsAtLevel
    }

    /** Highest Norma level held by a member of [team] (time-limit tie-break). */
    public fun topLevel(match: Match, team: TeamId): Int =
        match.teams.firstOrNull { it.id == team }?.members.orEmpty().maxOfOrNull { norma.get(match.id, it).level } ?: 1

    /** Stars carried by the members of [team] together (time-limit tie-break after the Norma level). */
    public fun carried(match: Match, team: TeamId): Int =
        match.teams.firstOrNull { it.id == team }?.members.orEmpty().sumOf { stars.get(match.id, it) }

    /** Kills made by the members of [team] together (last time-limit tie-break). */
    public fun kills(match: Match, team: TeamId): Int =
        match.teams.firstOrNull { it.id == team }?.members.orEmpty().sumOf { norma.get(match.id, it).totalKills }

    /**
     * Drops the match's Norma data. The final per-team standings are kept aside first so the result screen (which runs
     * after this on the same `MatchEnded`) can explain who was ahead; [takeFinalStandings] hands them over once.
     */
    public fun clear(matchId: MatchId) {
        if (isNorma(matchId)) {
            matches.findById(matchId)?.let { match ->
                val max = configOf(matchId).maxLevel
                finalStandings[matchId] = match.teams.map { NormaStanding(it.id, topLevel(match, it.id), max, carried(match, it.id), kills(match, it.id)) }
                while (finalStandings.size > MAX_KEPT_STANDINGS) finalStandings.keys.firstOrNull()?.let { finalStandings.remove(it) }
            }
        }
        norma.clear(matchId)
    }

    /** The standings [clear] kept for [matchId] (empty outside a Norma match); each match's are handed out once. */
    public fun takeFinalStandings(matchId: MatchId): List<NormaStanding> = finalStandings.remove(matchId).orEmpty()

    private val finalStandings: MutableMap<MatchId, List<NormaStanding>> = java.util.concurrent.ConcurrentHashMap()

    private fun evaluate(match: Match, playerId: PlayerId) {
        val cfg = configOf(match.id)
        val p = norma.get(match.id, playerId)
        if (p.level >= cfg.maxLevel) return
        val reached = when (p.goal) {
            NormaGoal.STAR -> stars.get(match.id, playerId) >= cfg.starsGoalAt(p.level)
            NormaGoal.KILL -> p.killsAtLevel >= cfg.killsGoalAt(p.level)
        }
        if (!reached) return
        val up = NormaProgress(level = p.level + 1, goal = NormaGoal.STAR, killsAtLevel = 0, totalKills = p.totalKills)
        norma.put(match.id, playerId, up)
        eventBus.publish(NormaLevelUp(match.id, playerId, up.level))
        if (up.level >= cfg.maxLevel && cfg.win) {
            val team = match.teams.firstOrNull { playerId in it.members }?.id ?: return
            lifecycle.end(match.id, EndReason.NormaLevelReached(team, up.level))
        }
    }

    private fun live(match: Match): Boolean = match.phase == MatchPhase.ACTIVE || match.phase == MatchPhase.SUDDEN_DEATH

    private companion object {
        /** Standings nobody collected (no result screen) must not pile up. */
        const val MAX_KEPT_STANDINGS = 32
    }
}
