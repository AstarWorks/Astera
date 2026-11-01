package com.astarworks.astera.gamesim.playable.agent

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.gamesim.GameSim
import com.astarworks.astera.gamesim.SimEntity
import com.astarworks.astera.gamesim.playable.MatchConfig
import com.astarworks.astera.gamesim.playable.MatchDriver
import com.astarworks.astera.gamesim.playable.SimBotHost
import com.astarworks.astera.gamesim.playable.SimTerrain

/** What the `new` op asks for (ADR-0316 §2.1). */
data class MatchRequest(
    val mode: String = "annihilation",
    val stage: String? = null,
    val teams: Int = 3,
    /** Seats per team: every enemy team has this many real bots; the agent's team has the agent plus (this - 1) bot mates. At least 1. */
    val botsPerTeam: Int = 3,
    /** Team of the agent's seat. */
    val me: String = "red",
    val seed: Long = GameSim.DEFAULT_SEED,
)

/**
 * The seam between the AgentPort and whatever runs the match (ADR-0316). The agent only ever touches the match through
 * this, so bot seats appear as ordinary entities in [sim].
 */
interface MatchHost : AutoCloseable {
    val sim: GameSim
    val request: MatchRequest
    val matchId: MatchId
    val stageId: String
    val tick: Long get() = sim.tick

    /** The generated stage terrain the seat walks on. */
    val terrain: SimTerrain

    /** True once the match is over (`MatchEnded` published). */
    val ended: Boolean

    /** The agent's own seat. */
    fun me(): SimEntity

    /** The agent seat's [SimBotHost]: position, physics and the bot-runner `Navigator` walk it like a bot. */
    fun meHost(): SimBotHost

    fun step(n: Int)

    /** Steps up to [maxTicks] one tick at a time until [cond] holds; returns whether it held. */
    fun stepUntil(maxTicks: Int, cond: () -> Boolean): Boolean
}

/** Starts a match for a [MatchRequest]. */
fun interface MatchHostFactory {
    fun boot(request: MatchRequest): MatchHost
}

/** The agent seat plus real [com.astarworks.astera.bots.BotBrain] bots in every other seat, run by [MatchDriver]. */
class DriverMatchHost private constructor(
    private val driver: MatchDriver,
    override val request: MatchRequest,
    override val stageId: String,
) : MatchHost {
    private val seat = driver.humanSeats.first()

    override val sim: GameSim get() = driver.sim
    override val matchId: MatchId get() = driver.matchId
    override val terrain: SimTerrain get() = driver.terrain
    override val ended: Boolean get() = driver.ended

    override fun me(): SimEntity = seat.entity
    override fun meHost(): SimBotHost = driver.hostOf(seat)
    override fun step(n: Int) = driver.step(n)
    override fun stepUntil(maxTicks: Int, cond: () -> Boolean): Boolean = driver.stepUntil(maxTicks, cond)
    override fun close() = driver.close()

    companion object : MatchHostFactory {
        override fun boot(request: MatchRequest): DriverMatchHost {
            val stageId = request.stage ?: StageLookup.laneStage(request.mode)
            val driver = MatchDriver.boot(
                MatchConfig(
                    mode = request.mode, stage = stageId, teams = emptyList(), teamCount = request.teams,
                    botsPerTeam = request.botsPerTeam.coerceAtLeast(1), seed = request.seed, humanTeam = request.me, humanSeats = 1,
                ),
            )
            return DriverMatchHost(driver, request, stageId)
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
