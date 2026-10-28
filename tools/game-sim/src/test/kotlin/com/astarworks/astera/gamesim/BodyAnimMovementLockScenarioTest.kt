package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.animation.ISkinRigAnimator
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.animation.player.CustomAnimationId
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.op.BodyOp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * WP-F: `caster_anim.speed` scales the skin-rig clip clock, `victim_anim` plays every known reaction and refuses an unknown
 * one at load, and a movement lock comes off at cast end, cancel and when the caster dies or quits.
 */
class BodyAnimMovementLockScenarioTest {

    private fun skill(id: String, gameplay: String, presentation: String = "{timeline: []}"): Pair<String, String> = id to """
        apiVersion: "astera/v2"
        kind: Skill
        id: $id
        item: pistol
        meta: {name: {ja: "t", en: "t"}, description: {ja: "t", en: "t"}, cooldown_ticks: 1, resource_cost: 0, tags: []}
        gameplay: $gameplay
        presentation: $presentation
    """.trimIndent()

    private val victim = simVictim(1)

    // ---- caster_anim.speed ----

    @Test
    fun `caster_anim speed reaches the gateway and defaults to 1`() {
        val s = skill(
            "t-speed", "{timeline: [{id: t0, at: 0, do: []}]}",
            "{timeline: [{id: a, at: 0, do: [{id: fast, op: caster_anim, anim: sword-swing, speed: 2.0}]}, {id: b, at: 2, do: [{id: plain, op: caster_anim, anim: wave}]}]}",
        )
        val sim = CastSim(victims = emptyMap(), extraSkills = mapOf(s))
        sim.cast("t-speed", 4)
        assertThat(sim.casterAnims.filter { it.verb == "play" }.map { Triple(it.tick, it.anim, it.speed) })
            .containsExactly(Triple(0, "sword-swing", 2.0), Triple(2, "wave", 1.0))
    }

    @Test
    fun `the skin rig runs a clip twice as fast at speed 2`() {
        fun ticksToFinish(speed: Double): Int {
            var ticks = 0
            scenario("anim-speed-$speed") {
                val caster = player("caster", team = "red")
                val animator = sim.services.getOrThrow<ISkinRigAnimator>()
                animator.play(caster.id, CustomAnimationId("sword-swing"), speed)
                while (animator.active(caster.id) != null && ticks < 80) { advance(1); ticks++ }
            }
            return ticks
        }
        val normal = ticksToFinish(1.0)
        val fast = ticksToFinish(2.0)
        val slow = ticksToFinish(0.5)
        assertThat(fast).isLessThan(normal)
        assertThat(slow).isGreaterThan(normal)
        assertThat(fast).isBetween(normal / 2 - 1, normal / 2 + 1)
        assertThat(slow).isBetween(normal * 2 - 2, normal * 2 + 2)
    }

    // ---- victim_anim ----

    @Test
    fun `every known victim reaction reaches the gateway with its strength`() {
        val reactions = BodyOp.VictimAnim.KNOWN.toList()
        assertThat(reactions).containsExactlyInAnyOrder("hurt", "stagger", "stagger_back")
        val ops = reactions.joinToString(", ") { "{id: v_$it, op: victim_anim, target: victim, anim: $it, strength: 0.5}" }
        val s = skill(
            "t-victim",
            "{timeline: [{id: t0, at: 0, do: [{id: sel, op: hit, frames: [caster], sector: {frame: caster, at: eye, forward: caster.forward, left: caster.left, angle_deg: 120, range: 4.0, height: 2.0}, max_count: 1, window: once}]}]}",
            "{timeline: [], on: {hit: [$ops]}}",
        )
        val sim = CastSim(victims = mapOf(victim to Vec3(0.0, 0.0, 2.0)), extraSkills = mapOf(s))
        sim.cast("t-victim", 3)
        assertThat(sim.victimAnims.map { it.anim }).containsExactlyInAnyOrderElementsOf(reactions)
        assertThat(sim.victimAnims.map { it.victim }.toSet()).containsExactly(victim)
        assertThat(sim.victimAnims.map { it.strength }.toSet()).containsExactly(0.5)
    }

    @Test
    fun `an unknown victim_anim is a load error`() {
        val s = skill(
            "t-bad-victim", "{timeline: [{id: t0, at: 0, do: []}]}",
            "{timeline: [{id: p0, at: 0, do: [{id: v, op: victim_anim, target: victim, anim: backflip}]}]}",
        )
        val dir = Files.createTempDirectory("bad-victim-skill")
        Files.createDirectories(dir.resolve(s.first))
        Files.writeString(dir.resolve(s.first).resolve("skill.yaml"), s.second)
        val failure = runCatching { CastSim(victims = emptyMap(), skillsDir = dir) }.exceptionOrNull()
        assertThat(failure).describedAs("the skill must fail to load").isNotNull()
        assertThat(failure!!.toString()).contains("backflip")
    }

    @Test
    fun `every victim_anim in a shipped skill is a known reaction`() {
        val skillsDir = Coin2CastWiring.collaborators().skillsDir
        val used = Files.walk(skillsDir).use { st ->
            st.filter { it.toString().endsWith(".yaml") }.toList().flatMap { f ->
                Regex("""op: victim_anim[^}]*?anim: ([\w-]+)""").findAll(Files.readString(f)).map { it.groupValues[1] }.toList()
            }
        }
        assertThat(used).isNotEmpty
        assertThat(BodyOp.VictimAnim.KNOWN).containsAll(used)
    }

    // ---- movement lock release ----

    private fun lockSkill() = skill("t-lock", "{timeline: [{id: t0, at: 0, do: [{id: l, op: lock, target: caster, speed: 0.0, ticks: 100}]}, {id: t9, at: 40, do: []}]}")

    @Test
    fun `a lock is held while casting and released when the cast is cancelled`() {
        val sim = CastSim(victims = emptyMap(), extraSkills = mapOf(lockSkill()))
        val cast = sim.cast("t-lock", 5)
        assertThat(sim.moveLocks).containsExactly(0 to 0.0)
        assertThat(sim.kit.locks.isLocked(SIM_CASTER)).isTrue()
        sim.kit.runner.cancel(cast)
        assertThat(sim.moveLocks.last()).isEqualTo(5 to null)
        assertThat(sim.kit.locks.isLocked(SIM_CASTER)).isFalse()
    }

    @Test
    fun `a lock is released when the cast ends on its own`() {
        val sim = CastSim(victims = emptyMap(), extraSkills = mapOf(lockSkill()))
        sim.cast("t-lock", 60)
        assertThat(sim.kit.locks.isLocked(SIM_CASTER)).isFalse()
        assertThat(sim.moveLocks.last().second).isNull()
    }

    @Test
    fun `a lock is released when the caster dies or quits mid-cast`() {
        val sim = CastSim(victims = emptyMap(), extraSkills = mapOf(lockSkill()))
        sim.start("t-lock")
        sim.step(3)
        assertThat(sim.kit.locks.isLocked(SIM_CASTER)).isTrue()
        sim.casterAlive = false
        sim.step(4)
        assertThat(sim.kit.locks.isLocked(SIM_CASTER)).isFalse()
        assertThat(sim.moveLocks.last().second).describedAs("the gateway is told to unlock").isNull()
    }

    @Test
    fun `plugin shutdown drops every lock`() {
        val sim = CastSim(victims = emptyMap(), extraSkills = mapOf(lockSkill()))
        sim.start("t-lock")
        sim.step(2)
        sim.kit.locks.shutdown()
        assertThat(sim.kit.locks.isLocked(SIM_CASTER)).isFalse()
        assertThat(sim.moveLocks.last().second).isNull()
    }
}
