package com.astarworks.astera.gamesim.playable.metrics

import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.port.outbound.IStructureTargets
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.gamesim.GameSim
import com.astarworks.astera.gamesim.SimEntity
import com.astarworks.astera.match.application.gamemode.annihilation.AnnihilationPillarTargets
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationConfig
import com.astarworks.astera.match.domain.gamemode.annihilation.PillarDamageTuning
import com.astarworks.astera.match.domain.match.MatchStateMachine
import java.util.Random

/**
 * GameSim-only stand-in for the bot-vs-bot `MatchDriver` (ADR-0316): a real annihilation match with scripted players.
 * Per team one "attacker" walks to the nearest enemy pillar and burns it with flame-burst, the others "roam": they
 * wander the stage and shoot the nearest enemy with a pistol. Deterministic per [seed].
 *
 * MatchDriver hand-off: replace this class in [MassPlayMain] by the MatchDriver (it implements [MatchRun]).
 */
class ScriptedAnnihilationRun(
    seed: Long,
    private val playersPerTeam: Int = 2,
    teamsOverride: List<String>? = null,
    config: AnnihilationConfig = DEFAULT_CONFIG,
) : MatchRun {

    override val sim: GameSim = GameSim.boot("mass-play-$seed", annihilation = config, seed = seed)
    private val rng = Random(seed)
    private val listeners = mutableListOf<(Long) -> Unit>()
    private val matchId: MatchId
    private val stage = checkNotNull(sim.services.getOrThrow<IStageRegistry>().find(StageId("annihilation-arena")))
    private val targets = sim.services.getOrThrow<IStructureTargets>()
    /** The stage's own spawn teams unless overridden: no team list is hardcoded here. */
    private val teams: List<String> = teamsOverride
        ?: stage.spawnPoints.filter { it.role == com.astarworks.astera.core.domain.stage.SpawnRole.TEAM }.mapNotNull { it.team?.value }.distinct()

    private class Seat(val entity: SimEntity, val team: String, val attacker: Boolean, var goal: Vec3, var nextAction: Long = 0)

    private val seats = mutableListOf<Seat>()

    init {
        val lifecycle = sim.services.getOrThrow<IMatchLifecycle>()
        for (team in teams) repeat(playersPerTeam) { i ->
            val spawn = stage.spawnPoints.firstOrNull { it.team?.value == team }?.position ?: Vec3(0.0, sim.world.groundY, 0.0)
            val entity = sim.spawnPlayer("$team-${i + 1}", Vec3(spawn.x + i, spawn.y, spawn.z), TeamId(team))
            val attacker = i == 0
            sim.hold(entity, if (attacker) "fire-staff" else "pistol")
            seats += Seat(entity, team, attacker, spawn)
        }
        matchId = (lifecycle.start(StageId("annihilation-arena"), "annihilation") as Result.Success).value
        for (s in seats) lifecycle.entry(matchId, s.entity.id, TeamId(s.team))
        lifecycle.switchPhase(matchId, MatchStateMachine.TransitionTrigger.CountdownComplete)
        sim.step(2)
    }

    override val ended: Boolean get() = sim.eventsOf<MatchEnded>().any { it.matchId == matchId }

    override fun onTick(listener: (Long) -> Unit) { listeners += listener }

    override fun step(n: Int) {
        repeat(n) {
            if (!ended) act()
            sim.step(1)
            val tick = sim.tick
            listeners.forEach { it(tick) }
        }
    }

    private fun act() {
        val tick = sim.tick
        for (seat in seats) {
            val e = seat.entity
            if (!sim.isAlive(e.id)) continue
            if (seat.attacker) attack(seat, tick) else roam(seat, tick)
        }
    }

    private fun standingPillars(team: String) = stage.pillars
        .filter { it.team.value != team }
        .filter { (targets.hitPoints(AnnihilationPillarTargets.idOf("${matchId.value}-${it.team.value}-${it.tier.ordinalValue}")) ?: 0.0) > 0.0 }

    private fun attack(seat: Seat, tick: Long) {
        val e = seat.entity
        val pillar = standingPillars(seat.team).minByOrNull { it.position.distance(e.position) * 1.0 + it.tier.ordinalValue * 50.0 } ?: return
        val spot = Vec3(pillar.centre.x, pillar.position.y, pillar.centre.z - 5.0)
        if (e.position.distance(spot) > 1.0) {
            walk(e, spot, 0.35)
        } else if (tick >= seat.nextAction) {
            e.lookAt(Vec3(pillar.centre.x, pillar.position.y, pillar.centre.z))
            sim.cast(e, "flame-burst")
            seat.nextAction = tick + 85
        }
    }

    private fun roam(seat: Seat, tick: Long) {
        val e = seat.entity
        val enemy = seats.filter { it.team != seat.team && sim.isAlive(it.entity.id) }.minByOrNull { it.entity.position.distance(e.position) }
        if (enemy != null && e.position.distance(enemy.entity.position) < 14.0) {
            e.lookAt(enemy.entity.position)
            if (tick >= seat.nextAction) { sim.rightClick(e); seat.nextAction = tick + 10 }
            return
        }
        if (e.position.distance(seat.goal) < 1.5 || tick % 400L == 0L) {
            val centre = stage.pillars.firstOrNull()?.position ?: e.position
            seat.goal = Vec3(centre.x + (rng.nextDouble() - 0.5) * 80, e.position.y, centre.z + (rng.nextDouble() - 0.5) * 80)
        }
        walk(e, seat.goal, 0.2)
    }

    private fun walk(e: SimEntity, to: Vec3, speed: Double) {
        val d = to - e.position
        val len = d.length()
        if (len < 1e-6) return
        val step = if (len <= speed) d else d * (speed / len)
        sim.moveTo(e, Vec3(e.position.x + step.x, e.position.y + step.y, e.position.z + step.z))
    }

    companion object {
        /** Short phases and fragile pillars so a scripted match reaches an end within a few minutes of sim time. */
        val DEFAULT_CONFIG: AnnihilationConfig = AnnihilationConfig(
            pillarHp = 40,
            pillarDamage = PillarDamageTuning(weaponMult = 1.0),
            buildPhaseTicks = 200,
            engagePhaseTicks = 2400,
            centerPhaseTicks = 2400,
            suddenDeathTicks = 1200,
        )
    }
}
