package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.application.services.getOrThrow
import java.nio.file.Path

/**
 * Scenario DSL. A scenario boots a [GameSim] from the real `content/`, runs
 * [block] against a [ScenarioScope] (spawn, act, advance virtual time, assert
 * with plain Kotlin / AssertJ), and always writes the JSON trace to
 * `build/sim-traces/<name>.json` — also when the block throws, so a failing
 * scenario can still be replayed.
 *
 * ```
 * scenario("pistol-kills-player") {
 *     val shooter = player("shooter", team = "red")
 *     val victim = player("victim", at = Vec3(0.0, groundY, 8.0), team = "blue")
 *     shooter.holds("pistol")
 *     repeat(4) { shooter.click(); advance(10) }
 *     assertThat(hp(victim)).isEqualTo(0.0)
 * }
 * ```
 */
public fun scenario(
    name: String,
    annihilation: com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationConfig? = null,
    seed: Long = GameSim.DEFAULT_SEED,
    persistenceOverlay: Boolean = false,
    block: ScenarioScope.() -> Unit,
): Path {
    val sim = GameSim.boot(name, annihilation = annihilation, seed = seed, persistenceOverlay = persistenceOverlay)
    var trace: Path? = null
    try {
        ScenarioScope(sim).block()
    } finally {
        trace = runCatching { sim.writeTrace() }.getOrNull()
        sim.shutdown()
    }
    return checkNotNull(trace) { "trace could not be written for scenario $name" }
}

/** Receiver of a scenario block. Everything on [sim] is reachable; the members below are the common verbs. */
public class ScenarioScope(public val sim: GameSim) {

    public val groundY: Double get() = sim.world.groundY

    public fun player(
        name: String,
        at: Vec3 = Vec3(0.0, groundY, 0.0),
        team: String? = null,
        facing: Vec3 = Vec3(0.0, 0.0, 1.0),
    ): SimEntity = sim.spawnPlayer(name, at, team?.let { TeamId(it) }, facing)

    public fun mob(name: String, at: Vec3, team: String? = null): SimEntity =
        sim.spawnMob(name, at, team?.let { TeamId(it) })

    public fun SimEntity.holds(itemId: String) {
        check(sim.give(this, itemId)) { "could not give $itemId to $name" }
        sim.hold(this, itemId)
    }

    public fun SimEntity.faces(target: SimEntity) {
        lookAt(target.position + Vec3(0.0, target.hitboxHeight / 2, 0.0))
    }

    public fun SimEntity.leftClick() {
        sim.leftClick(this)
    }

    public fun SimEntity.rightClick(sneaking: Boolean = false) {
        sim.rightClick(this, sneaking)
    }

    /** Walks to [to] (one `PlayerMoved`, as the Paper move adapter reports it for players in a match). */
    public fun SimEntity.moveTo(to: Vec3) {
        sim.moveTo(this, to)
    }

    /** Digs out the block at [cell] (publishes `BlockBroken` for players in a match). */
    public fun SimEntity.breaks(cell: Vec3) {
        sim.breakBlock(this, cell)
    }

    /** Block placed at [cell] in the simulated world, or `null`. */
    public fun blockAt(cell: Vec3): String? = sim.world.blockAt(com.astarworks.astera.core.domain.world.WorldId("world"), cell)?.key

    /** Presses Q with the held item; [sneaking] selects Shift+Q. The pistol reloads on Q. */
    public fun SimEntity.drop(sneaking: Boolean = false) {
        sim.drop(this, sneaking)
    }

    /** Current ammo / energy of [entity]. */
    public fun ammo(entity: SimEntity): Int = checkNotNull(sim.pool(entity)) { "${entity.name} has no resource pool" }.ammo

    /** The vanilla food level (the energy bar) shown for [entity], or `null` before the first write. */
    public fun foodLevel(entity: SimEntity): Int? = sim.foodLevel(entity)

    /** Native hotbar cooldown sweeps (reload / primary-binding cooldown) drawn so far. */
    public fun cooldownSweeps(): List<SimItemCooldownSweep.Sweep> = sim.sweeps.sweeps

    public fun energy(entity: SimEntity): Int = checkNotNull(sim.pool(entity)) { "${entity.name} has no resource pool" }.energy

    /** Casts [skillId] as if a bound item had requested it (see [GameSim.cast]). */
    public fun SimEntity.cast(skillId: String) {
        sim.cast(this, skillId)
    }

    public fun advance(ticks: Int) {
        sim.step(ticks)
    }

    public fun advanceUntil(maxTicks: Int, condition: () -> Boolean): Boolean = sim.stepUntil(maxTicks, condition)

    public fun hp(entity: SimEntity): Double = checkNotNull(sim.hp(entity)) { "no HP for ${entity.name}" }

    public inline fun <reified T : DomainEvent> eventsOf(): List<T> = sim.eventsOf<T>()

    /**
     * Forms a match for the queue lane / game mode [mode] on [stage] through the real
     * lifecycle facade: players take the given teams (and the stage's team spawns), the
     * countdown is skipped and the match is ACTIVE on return.
     */
    public fun startMatch(
        mode: String,
        teams: Map<SimEntity, String>,
        stage: String = "siege-flat",
    ): com.astarworks.astera.core.domain.match.MatchId {
        val lifecycle = sim.services.getOrThrow<com.astarworks.astera.match.application.port.inbound.IMatchLifecycle>()
        val matchId = when (val started = lifecycle.start(com.astarworks.astera.core.domain.stage.StageId(stage), mode)) {
            is com.astarworks.astera.core.domain.Result.Success -> started.value
            is com.astarworks.astera.core.domain.Result.Failure -> error("start failed: ${started.error}")
        }
        teams.forEach { (entity, team) -> lifecycle.entry(matchId, entity.id, TeamId(team)) }
        lifecycle.switchPhase(matchId, com.astarworks.astera.match.domain.match.MatchStateMachine.TransitionTrigger.CountdownComplete)
        sim.step(2)
        return matchId
    }
}
