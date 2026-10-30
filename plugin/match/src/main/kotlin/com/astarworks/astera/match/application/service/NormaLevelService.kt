package com.astarworks.astera.match.application.service

import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.port.outbound.IMatchNormaStore
import com.astarworks.astera.match.application.port.outbound.IMatchStarStore
import com.astarworks.astera.match.domain.event.NormaCheckReady
import com.astarworks.astera.match.domain.event.NormaLevelUp
import com.astarworks.astera.match.domain.event.StarsCollected
import com.astarworks.astera.match.domain.norma.NormaConfig
import com.astarworks.astera.match.domain.norma.NormaCheckError
import com.astarworks.astera.match.domain.norma.NormaError
import com.astarworks.astera.match.domain.norma.NormaGoal
import com.astarworks.astera.match.domain.norma.NormaHud
import com.astarworks.astera.match.domain.norma.NormaProgress
import com.astarworks.astera.match.domain.norma.NormaStanding

/**
 * Orange-Juice-style Norma levels, per (match, player). Everyone starts at level 1 with the STAR goal. At each level
 * the player holds one goal: carry `norma_stars_goal` stars (not consumed) or make `norma_kills_goal` kills at this
 * level. Reaching it only makes the player READY (a notice, [NormaCheckReady]); the level rises when the player runs
 * the Norma check ([check], `/astera norma check`) at their OWN team's base zone while the goal still holds (stars lost
 * on a death un-ready them). A check keeps the stars, resets kills and re-opens the choice at STAR. Levels
 * are an in-match power layer: reaching `norma_max_level` only stops further levelling and never ends the match (the
 * mode's own objective decides it). Everything is dropped at match end.
 */
public class NormaLevelService(
    private val matches: IMatchRepository,
    private val stars: IMatchStarStore,
    private val norma: IMatchNormaStore,
    private val configOf: (MatchId) -> NormaConfig,
    private val isNorma: (MatchId) -> Boolean,
    private val eventBus: IDomainEventBus,
    private val baseZone: NormaBaseZone,
    /** Carried across a Match rewire: ready players owing the check, and the standings kept at match end. */
    private val awaitingCheck: MutableSet<PlayerId> = java.util.concurrent.ConcurrentHashMap.newKeySet(),
    private val finalStandings: MutableMap<MatchId, List<NormaStanding>> = java.util.concurrent.ConcurrentHashMap(),
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

    /** `/astera norma check`: at the own base with the goal met, the level rises by one. */
    public fun check(playerId: PlayerId): Result<NormaProgress, NormaCheckError> {
        val match = matches.findMatchOf(playerId) ?: return Result.failure(NormaCheckError.NotInMatch)
        if (!isNorma(match.id)) return Result.failure(NormaCheckError.ModeHasNoNorma)
        if (!live(match)) return Result.failure(NormaCheckError.MatchNotRunning)
        val cfg = configOf(match.id)
        val p = norma.get(match.id, playerId)
        if (p.level >= cfg.maxLevel) return Result.failure(NormaCheckError.AtMaxLevel)
        when (baseZone.siteOf(match, playerId, cfg.checkRadius)) {
            BaseSite.OWN -> Unit
            BaseSite.ENEMY -> return Result.failure(NormaCheckError.EnemyBase)
            BaseSite.NONE -> return Result.failure(NormaCheckError.NotAtBase)
        }
        val missing = shortfall(match, p, playerId, cfg)
        if (missing > 0) {
            // Goal lost since the ready notice (died, spent): the notice fires again once it is met again.
            if (p.readyNotified) norma.put(match.id, playerId, p.copy(readyNotified = false))
            return Result.failure(NormaCheckError.NotReady(p.goal, missing))
        }
        val up = NormaProgress(level = p.level + 1, goal = NormaGoal.STAR, killsAtLevel = 0, totalKills = p.totalKills)
        norma.put(match.id, playerId, up)
        eventBus.publish(NormaLevelUp(match.id, playerId, up.level))
        return Result.success(up)
    }

    /**
     * Players who met their goal and still owe the check. Polled by [autoCheckReady]: walking into the own base zone while
     * ready runs the check by itself (the command stays for bots and tests).
     */
    /** Runs the Norma check for every ready player standing in their own base; the rest stay queued, dead ends drop out. */
    public fun autoCheckReady() {
        for (id in awaitingCheck.toList()) {
            val err = (check(id) as? Result.Failure)?.error
            when (err) {
                null, is NormaCheckError.NotReady, NormaCheckError.NotInMatch, NormaCheckError.ModeHasNoNorma,
                NormaCheckError.MatchNotRunning, NormaCheckError.AtMaxLevel -> awaitingCheck.remove(id)
                NormaCheckError.EnemyBase, NormaCheckError.NotAtBase -> Unit
            }
        }
    }

    /** The sidebar block of [playerId], or `null` outside a live Norma match. */
    public fun hudOf(playerId: PlayerId): NormaHud? {
        val match = matches.findMatchOf(playerId)?.takeIf { isNorma(it.id) } ?: return null
        val cfg = configOf(match.id)
        val p = norma.get(match.id, playerId)
        val carried = stars.get(match.id, playerId)
        val target = cfg.targetFor(p.level, p.goal)
        val progress = if (p.goal == NormaGoal.STAR) carried else p.killsAtLevel
        val ready = p.level < cfg.maxLevel && shortfall(match, p, playerId, cfg) == 0
        return NormaHud(carried, p.level, cfg.maxLevel, p.goal, progress.coerceAtMost(target), target, ready = ready)
    }

    /** [playerId]'s Norma level in their current match, or `null` outside a Norma match (no gate applies there). */
    public fun levelOf(playerId: PlayerId): Int? {
        val match = matches.findMatchOf(playerId)?.takeIf { isNorma(it.id) } ?: return null
        return norma.get(match.id, playerId).level
    }

    /** The Norma tunables of [playerId]'s current match, or `null` outside a Norma match. */
    public fun normaConfigOf(playerId: PlayerId): NormaConfig? {
        val match = matches.findMatchOf(playerId)?.takeIf { isNorma(it.id) } ?: return null
        return configOf(match.id)
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

    /** Marks [playerId] READY (once per level) when the goal is met; the level itself only rises in [check]. */
    private fun evaluate(match: Match, playerId: PlayerId) {
        val cfg = configOf(match.id)
        val p = norma.get(match.id, playerId)
        if (p.level >= cfg.maxLevel || p.readyNotified) return
        if (shortfall(match, p, playerId, cfg) > 0) return
        norma.put(match.id, playerId, p.copy(readyNotified = true))
        awaitingCheck += playerId
        eventBus.publish(NormaCheckReady(match.id, playerId, p.level))
    }

    /** How many stars / kills are still missing to clear the current level (0 = goal met). */
    private fun shortfall(match: Match, p: NormaProgress, playerId: PlayerId, cfg: NormaConfig): Int = when (p.goal) {
        NormaGoal.STAR -> (cfg.starsGoalAt(p.level) - stars.get(match.id, playerId)).coerceAtLeast(0)
        NormaGoal.KILL -> (cfg.killsGoalAt(p.level) - p.killsAtLevel).coerceAtLeast(0)
    }

    private fun live(match: Match): Boolean = match.phase == MatchPhase.ACTIVE || match.phase == MatchPhase.SUDDEN_DEATH

    private companion object {
        /** Standings nobody collected (no result screen) must not pile up. */
        const val MAX_KEPT_STANDINGS = 32
    }
}
