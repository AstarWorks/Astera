package com.astarworks.astera.gamesim.playable.agent

import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.service.StarterGrantLoader
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.stage.SpawnRole
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.gamesim.GameSim
import com.astarworks.astera.gamesim.ScenarioScope
import com.astarworks.astera.gamesim.SimEntity

/** What the `new` op asks for (ADR-0316 §2.1). */
data class MatchRequest(
    val mode: String = "annihilation",
    val stage: String? = null,
    val teams: Int = 3,
    val botsPerTeam: Int = 0,
    /** Team of the agent's seat. */
    val me: String = "red",
    val seed: Long = GameSim.DEFAULT_SEED,
)

/**
 * The seam between the AgentPort and whatever runs the match (ADR-0316). [SoloMatchHost] is the GameSim-only host
 * (the agent's seat alone); the bot-seat driver implements the same interface. The agent only ever touches the match
 * through this, so bot seats appear as ordinary entities in [sim].
 */
interface MatchHost : AutoCloseable {
    val sim: GameSim
    val request: MatchRequest
    val matchId: MatchId
    val stageId: String
    val tick: Long get() = sim.tick

    /** True once the match is over (`MatchEnded` published). */
    val ended: Boolean

    /** The agent's own seat. */
    fun me(): SimEntity

    fun step(n: Int)

    /** Steps up to [maxTicks] one tick at a time until [cond] holds; returns whether it held. */
    fun stepUntil(maxTicks: Int, cond: () -> Boolean): Boolean
}

/** Starts a match for a [MatchRequest]; the manager swaps in the bot-seat driver at merge. */
fun interface MatchHostFactory {
    fun boot(request: MatchRequest): MatchHost
}

/**
 * GameSim only: the agent's seat plus one inert stand-in per other team in the real wired match; no bot seats
 * ([MatchRequest.botsPerTeam] is not honoured here).
 */
class SoloMatchHost private constructor(
    override val sim: GameSim,
    override val request: MatchRequest,
    private val self: SimEntity,
    override val matchId: MatchId,
    override val stageId: String,
) : MatchHost {

    override val ended: Boolean get() = sim.events.any { it is MatchEnded && it.matchId == matchId }

    override fun me(): SimEntity = self

    override fun step(n: Int) = sim.step(n)

    override fun stepUntil(maxTicks: Int, cond: () -> Boolean): Boolean = sim.stepUntil(maxTicks, cond)

    override fun close() = sim.shutdown()

    companion object : MatchHostFactory {
        override fun boot(request: MatchRequest): SoloMatchHost {
            val sim = GameSim.boot("play-${request.mode}-${request.seed}", seed = request.seed)
            val scope = ScenarioScope(sim)
            val stageId = request.stage ?: StageLookup.laneStage(request.mode)
            val stage = checkNotNull(sim.services.getOrThrow<IStageRegistry>().find(StageId(stageId))) { "unknown stage '$stageId'" }
            val teams = stage.spawnPoints.filter { it.role == SpawnRole.TEAM }.mapNotNull { it.team?.value }.distinct()
            check(request.me in teams) { "team '${request.me}' is not on stage $stageId (teams: $teams)" }
            val seat = scope.player("Claude", team = request.me)
            // A match with one occupied team ends at once (every other team is "eliminated"), so each other team gets an
            // inert stand-in that never acts: the seat plays a living match, and the stand-ins are targets for `attack`.
            val standIns = teams.filter { it != request.me }.take((request.teams - 1).coerceAtLeast(1)).associate { team ->
                scope.player("idle-$team", team = team) to team
            }
            val matchId = scope.startMatch(request.mode, mapOf(seat to request.me) + standIns, stageId)
            // The Paper adapter lends the field tools on match entry (starter-grant.yaml `match_tools`); the sim has no inventory adapter.
            StarterGrantLoader().load(GameSim.defaultContentDir().resolve("balance/starter-grant.yaml")).matchTools
                .forEach { sim.server.view(seat.id)?.receive(it) }
            return SoloMatchHost(sim, request, seat, matchId, stageId)
        }
    }
}

internal object StageLookup {
    /** The stage the shipped `matchmaking/queue.yaml` routes lane [mode] to. */
    fun laneStage(mode: String): String {
        val queue = com.astarworks.astera.match.application.contents.QueueSettingsContents()
        queue.bootstrap(GameSim.defaultContentDir().resolve("matchmaking"))
        return queue.current().lanes[mode]?.stage?.value ?: error("no matchmaking lane '$mode' in content/matchmaking/queue.yaml")
    }
}
