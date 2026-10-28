package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * SKL-14 (displays never disappear): a cast owns every display it creates and releases them when it ends, however it
 * ends. Casts every shipped skill, ends them normally, by cancel and by caster interruption, and asserts that no visual
 * handle is left, no cast is left running and the visual port never saw a stale update or a double remove. A projectile
 * body that meets a wall takes its meshes with it on the contact tick, and the cast does not outlive its motion.
 */
class CastDespawnScenarioTest {
    private val origin = Vec3(37.5, -60.0, -12.5)
    private val stacks = mapOf("charge" to 5, "bleed" to 5, "burn" to 5)

    private fun dummies() = (1..4).associate { simVictim(it) to ahead(origin, 30.0, 2.0 * it) }

    private fun ids(): List<String> =
        CastSim(dummies(), origin = origin, yawDeg = 30.0).catalog.ids().filter { '@' !in it }.sorted()

    private fun sim(rec: LeakTrackingVisuals, d: Map<java.util.UUID, Vec3>, blockAtZ: Double? = null): CastSim =
        CastSim(d, origin = origin, yawDeg = 30.0, casterStacks = stacks, visualGateway = rec, blockAtZ = blockAtZ)

    private fun runToQuiet(sim: CastSim, from: Int) {
        sim.step(from + MAX_CAST_TICKS + 5)
    }

    private fun report(problems: List<String>) = assertThat(problems).describedAs("despawn problems").isEmpty()

    @Test
    fun `every shipped skill leaves no visual handle after it ends normally or is cancelled`() {
        val problems = ArrayList<String>()
        val ids = ids()
        assertThat(ids).hasSizeGreaterThanOrEqualTo(149)
        for (id in ids) {
            for (cancelAt in listOf(-1, 3, 25, 70)) {
                lateinit var s: CastSim
                val rec = LeakTrackingVisuals { s.now }
                s = sim(rec, dummies())
                try {
                    val cast = s.start(id)
                    if (cancelAt >= 0) {
                        s.step(cancelAt)
                        s.kit.runner.cancel(cast)
                    }
                    runToQuiet(s, s.now)
                    if (rec.live.isNotEmpty()) problems += "$id cancel@$cancelAt: live ${rec.live.values.groupingBy { it }.eachCount()}"
                    if (rec.stale.isNotEmpty()) problems += "$id cancel@$cancelAt: ${rec.stale.take(2)}"
                    if (s.kit.runner.cancelAll() != 0) problems += "$id cancel@$cancelAt: a cast was still running after $MAX_CAST_TICKS ticks"
                } catch (e: Throwable) {
                    problems += "$id cancel@$cancelAt: ${e::class.simpleName}: ${e.message?.take(200)}"
                }
            }
        }
        report(problems)
    }

    @Test
    fun `an interrupted caster loses every cast and display at once`() {
        val problems = ArrayList<String>()
        for (id in ids()) {
            lateinit var s: CastSim
            val rec = LeakTrackingVisuals { s.now }
            s = sim(rec, dummies())
            try {
                s.start(id)
                s.step(12)
                val ended = s.kit.runner.cancelAllOf(SIM_CASTER)
                if (ended == 0 && rec.live.isNotEmpty()) problems += "$id: displays alive with no cast to own them"
                // Only chained casts started by an END handler may follow; they must end on their own.
                runToQuiet(s, s.now)
                if (rec.live.isNotEmpty()) problems += "$id: live after interruption ${rec.live.values.groupingBy { it }.eachCount()}"
                if (rec.stale.isNotEmpty()) problems += "$id: ${rec.stale.take(2)}"
            } catch (e: Throwable) {
                problems += "$id: ${e::class.simpleName}: ${e.message?.take(200)}"
            }
        }
        report(problems)
    }

    @Test
    fun `a projectile body that meets a wall takes its displays with it on the contact tick`() {
        val skillsDir = Coin2CastWiring.collaborators().skillsDir
        val projectileSkills = ids().filter { id ->
            Files.walk(skillsDir.resolve(id)).use { st ->
                val text = st.filter { it.toString().endsWith(".yaml") }.map { Files.readString(it) }.toList().joinToString("\n")
                Regex("""op:\s*projectile\b""").containsMatchIn(text) && Regex("""op:\s*(mesh|plate)\b""").containsMatchIn(text)
            }
        }
        assertThat(projectileSkills).describedAs("shipped skills with a projectile that carries a mesh or plate").isNotEmpty()
        val problems = ArrayList<String>()
        for (id in projectileSkills) {
            lateinit var s: CastSim
            val rec = LeakTrackingVisuals { s.now }
            // The wall is 6 blocks ahead of the caster along its facing; nothing stands in the way.
            s = CastSim(emptyMap(), origin = origin, yawDeg = 0.0, casterStacks = stacks, visualGateway = rec, blockAtZ = origin.z + 6.0)
            try {
                s.start(id)
                s.step(80)
                val bodies = rec.kind.filterValues { it.startsWith("mesh") }.keys
                if (bodies.isEmpty()) { problems += "$id: spawned no mesh"; continue }
                for (h in bodies) {
                    val gone = rec.removedAt[h]
                    val z = rec.lastOrigin[h]?.z
                    if (gone == null) problems += "$id: mesh ${rec.kind[h]} still shown 80 ticks after the shot hit the wall"
                    else if (z != null && z < origin.z + 3.0 && gone < 8) problems += "$id: mesh removed at tick $gone before it reached the wall (z=$z)"
                }
                if (rec.live.isNotEmpty()) problems += "$id: live after the wall ${rec.live.values.groupingBy { it }.eachCount()}"
                if (s.kit.runner.cancelAll() != 0) problems += "$id: cast still running 80 ticks after its projectile hit the wall"
            } catch (e: Throwable) {
                problems += "$id: ${e::class.simpleName}: ${e.message?.take(200)}"
            }
        }
        report(problems)
    }

    private companion object {
        const val MAX_CAST_TICKS = 1200
    }
}
