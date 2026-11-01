package com.astarworks.astera.gamesim.playable

import com.astarworks.astera.bots.BotBrain
import com.astarworks.astera.bots.BotSkill
import com.astarworks.astera.bots.StageObjectives
import com.astarworks.astera.core.application.port.outbound.IBotPlayers
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.application.service.InMemoryPlayerInventorySnapshotRepository
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.stage.SpawnRole
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.gamesim.GameSim
import com.astarworks.astera.gamesim.SimEntity
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.gamesim.playable.metrics.MatchRun
import com.astarworks.astera.match.domain.match.MatchStateMachine
import java.util.Random

data class MatchConfig(
    val mode: String = "annihilation",
    val stage: String = "annihilation-arena",
    /** Teams in play, in spawn order; empty = the stage's own teams (at most [teamCount] when it is above 0, [humanTeam] always kept). */
    val teams: List<String> = listOf("red", "blue", "yellow"),
    val teamCount: Int = 0,
    /** Seats per team (bots plus, on [humanTeam], the human seats). */
    val botsPerTeam: Int = 3,
    /** Per-team override of [botsPerTeam]. */
    val teamSizes: Map<String, Int> = emptyMap(),
    val seed: Long = GameSim.DEFAULT_SEED,
    /** The first [humanSeats] seats of this team are human seats: players with no brain, driven by the caller through [hostOf]. */
    val humanTeam: String? = null,
    val humanSeats: Int = 1,
    /** Server-wide UI language (`en`, `ja`; ADR-0283): the language the boss bar and other i18n text reach the bots in. */
    val locale: String = "en",
)

/** One player of the match: [brain] is `null` for a human seat. */
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
    override val sim: GameSim,
    val seats: List<Seat>,
    private val hub: SimHostHub,
    val matchId: MatchId,
) : MatchRun, AutoCloseable {

    private val listeners = ArrayList<(Long) -> Unit>()
    private val errors = ArrayList<String>()
    private var endedAt: MatchEnded? = null

    /** The generated stage terrain the match runs on (walkable view, plan, kill plane). */
    val terrain: SimTerrain get() = hub.terrain

    /** The current virtual tick. */
    val tick: Long get() = sim.tick

    /** The match reached `MatchEnded` (win, timeout, all eliminated, ...). */
    override val ended: Boolean get() = endEvent() != null

    /** The `MatchEnded` event once [ended]. */
    fun endEvent(): MatchEnded? {
        if (endedAt == null) endedAt = sim.eventsOf<MatchEnded>().firstOrNull { it.matchId == matchId }
        return endedAt
    }

    /** Exceptions thrown by brains (the live runner swallows and logs them; here they are kept to be asserted on). */
    val brainErrors: List<String> get() = errors

    /** The host behind a seat (also the human seat's: the caller drives it, there is no brain). */
    fun hostOf(seat: Seat): SimBotHost = checkNotNull(hub.hosts[seat.entity.id]) { "no host for ${seat.name}" }

    /** The seats of the human team without a brain, in seat order. */
    val humanSeats: List<Seat> get() = seats.filter { it.brain == null }

    /** The brain of each bot seat by player, for metrics hooks. */
    override fun brainOf(player: com.astarworks.astera.core.domain.player.PlayerId): BotBrain? = seats.firstOrNull { it.entity.id == player }?.brain

    override val seatOrder: List<String> get() = seats.map { "${it.name}@${it.team}" }

    /** Registers [listener], called with the new tick after every step. */
    override fun onTick(listener: (Long) -> Unit) { listeners += listener }

    override fun step(n: Int) {
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
        const val HUMAN_PREFIX = "Human_"

        private fun resolveTeams(config: MatchConfig, stageTeams: List<String>): List<String> {
            if (config.teams.isNotEmpty()) return config.teams
            val human = config.humanTeam
            check(human == null || human in stageTeams) { "team '$human' is not on stage ${config.stage} (teams: $stageTeams)" }
            if (config.teamCount <= 0 || config.teamCount >= stageTeams.size) return stageTeams
            val keep = (stageTeams - listOfNotNull(human).toSet()).take(config.teamCount - (if (human != null) 1 else 0)).toSet() + listOfNotNull(human)
            return stageTeams.filter { it in keep }
        }

        fun boot(config: MatchConfig): MatchDriver {
            val contentDir = GameSim.defaultContentDir()
            val sim = GameSim.boot("playable-${config.mode}", seed = config.seed, snapshots = InMemoryPlayerInventorySnapshotRepository(), defaultLocale = com.astarworks.astera.core.domain.i18n.LocaleCode(config.locale))
            try {
                val stage = checkNotNull(sim.services.getOrThrow<IStageRegistry>().find(StageId(config.stage))) { "unknown stage ${config.stage}" }
                // The server-side base-shop buyer (EconomyWiring) only serves accounts this port calls bots, as on the live server.
                sim.services.register<IBotPlayers>(IBotPlayers { id -> sim.world.find(id)?.name?.startsWith("Bot_") == true })
                val terrain = SimTerrainLoader.load(sim, stage, contentDir)
                val hub = SimHostHub(sim, terrain)

                // Everyone joins first, then the real lifecycle puts them on their team spawns.
                val parked = Vec3(0.0, terrain.plan.surfaceY + 1.0, 0.0)
                val roster = ArrayList<Pair<SimEntity, String>>()
                val teams = resolveTeams(config, stage.spawnPoints.filter { it.role == SpawnRole.TEAM }.mapNotNull { it.team?.value }.distinct())
                for (team in teams) {
                    for (i in 1..(config.teamSizes[team] ?: config.botsPerTeam)) {
                        val human = team == config.humanTeam && i <= config.humanSeats
                        val name = if (human) "Human_${team}_$i" else "Bot_${team}_$i"
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
                    val brain = if (entity.name.startsWith(HUMAN_PREFIX)) null else
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
