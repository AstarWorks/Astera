package com.astarworks.astera.gamesim.playable

import com.astarworks.astera.bots.BotBrain
import com.astarworks.astera.bots.BotSkill
import com.astarworks.astera.bots.StageObjectives
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.service.InMemoryPlayerInventorySnapshotRepository
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.gamesim.GameSim
import com.astarworks.astera.gamesim.SimEntity
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.domain.match.MatchStateMachine
import java.util.Random

data class MatchConfig(
    val mode: String = "annihilation",
    val stage: String = "annihilation-arena",
    val teams: List<String> = listOf("red", "blue", "yellow"),
    val botsPerTeam: Int = 3,
    val seed: Long = GameSim.DEFAULT_SEED,
    /** This team's first seat is a human seat: a player with no brain, driven by the caller through [hostOf]. */
    val humanTeam: String? = null,
)

/** One player of the match: [brain] is `null` for the human seat. */
class Seat(val name: String, val team: String, val entity: SimEntity, val brain: BotBrain?)

/**
 * A complete match run headless on game-sim (ADR-0316): real wiring, real `content/`, the generated stage terrain, and the
 * real [BotBrain] driving every bot seat through a [SimBotHost]. Time is virtual: one [step] = brains tick, hosts do
 * their physics, the sim advances one tick (so a 20-minute match is a few seconds of wall time).
 *
 * The `Bot_` name prefix gets each bot the bot loadout (two weapons) from `IMatchEntryLoadout`, like live bots.
 */
class MatchDriver private constructor(
    val config: MatchConfig,
    val sim: GameSim,
    val seats: List<Seat>,
    private val hub: SimHostHub,
    val matchId: MatchId,
) : AutoCloseable {

    private val listeners = ArrayList<(Long) -> Unit>()
    private val errors = ArrayList<String>()
    private var endedAt: MatchEnded? = null

    /** The current virtual tick. */
    val tick: Long get() = sim.tick

    /** The match reached `MatchEnded` (win, timeout, all eliminated, ...). */
    val ended: Boolean get() = endEvent() != null

    /** The `MatchEnded` event once [ended]. */
    fun endEvent(): MatchEnded? {
        if (endedAt == null) endedAt = sim.eventsOf<MatchEnded>().firstOrNull { it.matchId == matchId }
        return endedAt
    }

    /** Exceptions thrown by brains (the live runner swallows and logs them; here they are kept to be asserted on). */
    val brainErrors: List<String> get() = errors

    /** The host behind a seat (also the human seat's: the caller drives it, there is no brain). */
    fun hostOf(seat: Seat): SimBotHost = checkNotNull(hub.hosts[seat.entity.id]) { "no host for ${seat.name}" }

    /** Registers [listener], called with the new tick after every step. */
    fun onTick(listener: (Long) -> Unit) { listeners += listener }

    fun step(n: Int = 1) {
        repeat(n) {
            for (seat in seats) {
                val host = hostOf(seat)
                seat.brain?.let { brain ->
                    try { brain.tick() } catch (t: Throwable) {
                        if (errors.size < MAX_ERRORS) errors += "${seat.name}@${sim.tick}: $t"
                    }
                }
                host.afterBrain()
            }
            sim.step(1)
            hub.pumpEvents()
            val now = sim.tick
            listeners.forEach { it(now) }
        }
    }

    /** Steps until [cond] holds or [maxTicks] elapse; returns whether it held. */
    fun stepUntil(maxTicks: Int, cond: () -> Boolean): Boolean {
        repeat(maxTicks) {
            if (cond()) return true
            step(1)
        }
        return cond()
    }

    override fun close() {
        seats.forEach { it.brain?.close() }
        sim.shutdown()
    }

    companion object {
        private const val MAX_ERRORS = 50

        fun boot(config: MatchConfig): MatchDriver {
            val contentDir = GameSim.defaultContentDir()
            val sim = GameSim.boot("playable-${config.mode}", seed = config.seed, snapshots = InMemoryPlayerInventorySnapshotRepository())
            try {
                val stage = checkNotNull(sim.services.getOrThrow<IStageRegistry>().find(StageId(config.stage))) { "unknown stage ${config.stage}" }
                val terrain = SimTerrainLoader.load(sim, stage, contentDir)
                val hub = SimHostHub(sim, terrain)

                // Everyone joins first, then the real lifecycle puts them on their team spawns.
                val parked = Vec3(0.0, terrain.plan.surfaceY + 1.0, 0.0)
                val roster = ArrayList<Pair<SimEntity, String>>()
                for (team in config.teams) {
                    for (i in 1..config.botsPerTeam) {
                        val human = team == config.humanTeam && i == 1
                        val name = if (human) "Human_$team" else "Bot_${team}_$i"
                        roster += sim.spawnPlayer(name, at = parked, team = TeamId(team), worldId = terrain.world) to team
                    }
                }
                sim.step(5)
                val lifecycle = sim.services.getOrThrow<IMatchLifecycle>()
                val matchId = when (val started = lifecycle.start(StageId(config.stage), config.mode)) {
                    is Result.Success -> started.value
                    is Result.Failure -> error("match start failed: ${started.error}")
                }
                roster.forEach { (entity, team) -> lifecycle.entry(matchId, entity.id, TeamId(team)) }
                lifecycle.switchPhase(matchId, MatchStateMachine.TransitionTrigger.CountdownComplete)
                sim.step(2)

                val objectives = StageObjectives.load(contentDir.toFile(), config.mode)
                val seats = roster.mapIndexed { index, (entity, team) ->
                    val host = SimBotHost(sim, hub, entity)
                    val brain = if (entity.name.startsWith("Human_")) null else
                        BotBrain(host, BotSkill(), objectives, true, config.mode, Random(config.seed + index)).also { it.mode = BotBrain.Mode.FIGHT }
                    Seat(entity.name, team, entity, brain)
                }
                return MatchDriver(config, sim, seats, hub, matchId)
            } catch (t: Throwable) {
                sim.shutdown()
                throw t
            }
        }
    }
}
