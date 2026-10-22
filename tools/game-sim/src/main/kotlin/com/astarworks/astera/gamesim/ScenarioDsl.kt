package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.TeamId
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
 *     shooter.holds("example-pistol")
 *     repeat(4) { shooter.click(); advance(10) }
 *     assertThat(hp(victim)).isEqualTo(0.0)
 * }
 * ```
 */
public fun scenario(name: String, block: ScenarioScope.() -> Unit): Path {
    val sim = GameSim.boot(name)
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

    /** Presses the drop key (Q) with the held item. */
    public fun SimEntity.drop() {
        sim.drop(this)
    }

    /** Current ammo / mana / stamina of [entity]. */
    public fun ammo(entity: SimEntity): Int = checkNotNull(sim.pool(entity)) { "${entity.name} has no resource pool" }.ammo

    public fun mana(entity: SimEntity): Int = checkNotNull(sim.pool(entity)) { "${entity.name} has no resource pool" }.mana

    public fun stamina(entity: SimEntity): Int = checkNotNull(sim.pool(entity)) { "${entity.name} has no resource pool" }.stamina

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
}
