package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.skillengine.runtime.CasterInterrupted
import com.astarworks.astera.core.application.skillengine.runtime.CastRequest
import com.astarworks.astera.core.application.skillengine.runtime.CastRunnerDeps
import com.astarworks.astera.core.application.skillengine.runtime.DefaultCastRunner
import com.astarworks.astera.core.application.skillengine.runtime.DefaultOpExecutorRegistry
import com.astarworks.astera.core.application.skillengine.runtime.MissingExecutors
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillEntityQuery
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillGameplayGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillParticleGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillSoundGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVictimAnimGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVisualGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.SkillRuntimePorts
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * WP-1 cast lifecycle (SKL-17 audit): nothing is silently skipped or dropped. Real runner, real loader, shipped
 * grammar; one scenario per fix.
 */
class SkillCastLifecycleScenarioTest {

    private val target = simVictim(1)

    private fun flat(yaml: String) = yaml.lines().joinToString(" ") { it.trim() }

    private fun skill(id: String, gameplay: String, presentation: String = "{timeline: []}", frames: String = "[]"): Pair<String, String> =
        id to """
            apiVersion: "astera/v2"
            kind: Skill
            id: $id
            item: pistol
            meta: {name: {ja: "t", en: "t"}, description: {ja: "t", en: "t"}, cooldown_ticks: 1, resource_cost: 0, tags: []}
            frames: ${flat(frames)}
            gameplay: ${flat(gameplay)}
            presentation: ${flat(presentation)}
        """.trimIndent()

    private inline fun <reified T> noop(): T = java.lang.reflect.Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, _ ->
        when (m.returnType) {
            java.lang.Boolean.TYPE -> true
            Integer.TYPE -> 0
            java.lang.Double.TYPE -> 0.0
            java.lang.Long.TYPE -> 0L
            else -> null
        }
    } as T

    // ---- 1. an op with no executor refuses the cast at start ----

    @Test
    fun `a skill whose ops have no executor does not start and names the ops`() {
        val sim = CastSim(victims = emptyMap(), extraSkills = mapOf(skill("t-noexec", "{timeline: [{id: t0, at: 0, do: [{id: mark, op: counter, key: k, delta: 1}]}]}")))
        val c = Coin2CastWiring.collaborators()
        val ports = SkillRuntimePorts(noop<ISkillVisualGateway>(), noop<ISkillParticleGateway>(), noop<ISkillSoundGateway>(), noop<ISkillGameplayGateway>(), noop<ISkillEntityQuery>(), noop<ISkillVictimAnimGateway>())
        val kit = sim.kit
        val empty = DefaultCastRunner(
            CastRunnerDeps(
                portsIn = { ports }, newTree = c.newTree, motion = c.motion, contacts = c.contacts, exprs = c.exprs,
                executors = DefaultOpExecutorRegistry(), points = c.points, hosts = kit.hosts, dashes = kit.dashes, locks = kit.locks,
                world = kit.world, presentation = kit.presentation, glow = kit.glow, cooldowns = noop(), items = noop(), effects = noop(),
                skills = sim.catalog::get,
            ),
        )
        val skill = requireNotNull(sim.catalog.get("t-noexec"))
        val result = empty.start(CastRequest(skill, SIM_CASTER, sim.castWorld, Vec3.ZERO, 0.0, 0.0, seed = 1L, startTick = 0))
        val error = (result as Result.Failure).error
        assertThat(error).isInstanceOf(MissingExecutors::class.java)
        assertThat((error as MissingExecutors).ops).anyMatch { it.startsWith("mark ") }
        assertThat(empty.diagnostics()).isEmpty()
    }

    @Test
    fun `every shipped skill has an executor for every op it uses`() {
        val sim = CastSim(victims = emptyMap())
        val missing = sim.catalog.ids().associateWith { sim.kit.runner.missingExecutors(requireNotNull(sim.catalog.get(it))) }.filterValues { it.isNotEmpty() }
        assertThat(missing).describedAs("skills with ops that have no executor").isEmpty()
    }

    // ---- 2. hp_ratio of something that is not an entity fails the op loudly ----

    @Test
    fun `hp_ratio without a victim in scope is a reported failure, not 1_0`() {
        val s = skill("t-hp", "{timeline: [{id: t0, at: 0, do: [{id: g, op: status, when: \"= hp_ratio(victim) < 2.0\", status: marked, target: caster, stacks: 1, duration_ticks: 5}]}]}")
        val sim = CastSim(victims = emptyMap(), extraSkills = mapOf(s))
        sim.start("t-hp")
        sim.step(3)
        assertThat(sim.kit.runner.diagnostics()).anyMatch { it.opId == "g" && it.message.contains("hp_ratio") }
        assertThat(sim.statuses).isEmpty()
    }

    @Test
    fun `hp_ratio of a struck victim resolves the real entity`() {
        val s = skill(
            "t-hp2",
            """
                {timeline: [{id: t0, at: 0, do: [{id: sw, op: hit, frames: [caster], window: once,
                  sector: {frame: caster, at: eye, forward: caster.forward, left: caster.left, angle_deg: 90, range: 4.0, height: 2.0}}]}],
                 "on": {hit: [{id: g, op: status, when: "= hp_ratio(victim) < 2.0", status: marked, target: victim, stacks: 1, duration_ticks: 5}]}}
            """.trimIndent(),
        )
        val sim = CastSim(victims = mapOf(target to Vec3(0.0, 0.0, 2.0)), extraSkills = mapOf(s))
        sim.cast("t-hp2", 4)
        assertThat(sim.statuses.filter { it.third == "marked" && it.second == target }).hasSize(1)
    }

    // ---- 3. re-arming replaces the listener ----

    @Test
    fun `a re-armed projectile runs its on_hit exactly once`() {
        val s = skill(
            "t-rearm",
            """
                {timeline: [{id: t0, at: 0, do: [{id: l, op: loop, count: 2, every_ticks: 3, do: [
                  {id: bolt, op: projectile, body: bolt, from: muzzle, velocity: [0.0, 0.0, 1.0], max_ticks: 30, radius: 0.2,
                   on_hit: [{id: dmg, op: damage, components: [{kind: physical, amount: 7}], target: victim}]}]}]}]}
            """.trimIndent(),
            frames = "[{id: bolt, kind: body, parent: world, inactive: true}, {id: muzzle, kind: point, parent: caster.eye, offset: [0.0, 0.0, 0.5]}]",
        )
        val sim = CastSim(victims = mapOf(target to Vec3(0.0, 0.0, 9.0)), extraSkills = mapOf(s))
        sim.cast("t-rearm", 30)
        assertThat(sim.dealt).describedAs("one projectile body, one hit, one on_hit run").hasSize(1)
    }

    // ---- 4. a sector does not reach through a wall ----

    private val sector = skill(
        "t-wall",
        """
            {timeline: [{id: t0, at: 0, do: [{id: sw, op: hit, frames: [caster], window: once,
              sector: {frame: caster, at: eye, forward: caster.forward, left: caster.left, angle_deg: 90, range: 5.0, height: 2.0}}]}],
             "on": {hit: [{id: dmg, op: damage, components: [{kind: physical, amount: 10}], target: victim}]}}
        """.trimIndent(),
    )

    @Test
    fun `a sector swing is stopped by a wall between the apex and the victim`() {
        val open = CastSim(victims = mapOf(target to Vec3(0.0, 0.0, 3.0)), extraSkills = mapOf(sector))
        open.cast("t-wall", 3)
        assertThat(open.dealt).describedAs("no wall: hit").hasSize(1)
        val walled = CastSim(victims = mapOf(target to Vec3(0.0, 0.0, 3.0)), blockAtZ = 1.5, extraSkills = mapOf(sector))
        walled.cast("t-wall", 3)
        assertThat(walled.dealt).describedAs("wall at z 1.5").isEmpty()
        val nearer = CastSim(victims = mapOf(target to Vec3(0.0, 0.0, 1.0)), blockAtZ = 1.5, extraSkills = mapOf(sector))
        nearer.cast("t-wall", 3)
        assertThat(nearer.dealt).describedAs("victim in front of the wall").hasSize(1)
    }

    // ---- 5. an event storm is reported once and nothing is dropped ----

    @Test
    fun `more than 256 events in one phase are all delivered and the overflow is reported once`() {
        val s = skill(
            "t-storm",
            """
                {timeline: [{id: t0, at: 0, do: [{id: l, op: loop, count: 300, do: [{id: r, op: raise_event, name: ping}]}]}],
                 "on": {ping: [{id: m, op: status, status: marked, target: caster, stacks: 1, duration_ticks: 5}]}}
            """.trimIndent(),
        )
        val sim = CastSim(victims = emptyMap(), extraSkills = mapOf(s))
        sim.start("t-storm")
        sim.step(8)
        assertThat(sim.statuses.count { it.third == "marked" }).describedAs("every ping delivered").isEqualTo(300)
        assertThat(sim.kit.runner.diagnostics().filter { it.message.startsWith("event storm") }).hasSize(1)
    }

    // ---- 6. stun / silence ends a cast ----

    private val longCast = skill(
        "t-long",
        "{timeline: [{id: t0, at: 0, do: []}, {id: t10, at: 10, do: [{id: late, op: status, status: marked, target: caster, stacks: 1, duration_ticks: 5}]}, {id: t30, at: 30, do: []}]}",
    )

    @Test
    fun `a caster shocked mid-cast stops casting`() {
        val stacks = HashMap<String, Int>()
        val sim = CastSim(victims = emptyMap(), casterStacks = stacks, extraSkills = mapOf(longCast))
        val id = sim.start("t-long")
        sim.step(5)
        assertThat(sim.kit.runner.isRunning(id)).isTrue()
        stacks["shock"] = 1
        sim.step(6)
        assertThat(sim.kit.runner.isRunning(id)).describedAs("cancelled by the shock").isFalse()
        sim.step(20)
        assertThat(sim.statuses.filter { it.third == "marked" }).describedAs("the tick-10 row never ran").isEmpty()
    }

    @Test
    fun `a silenced caster cannot start a cast`() {
        val sim = CastSim(victims = emptyMap(), casterStacks = mapOf("silence" to 1), extraSkills = mapOf(longCast))
        val skill = requireNotNull(sim.catalog.get("t-long"))
        val r = sim.kit.runner.start(CastRequest(skill, SIM_CASTER, sim.castWorld, Vec3.ZERO, 0.0, 0.0, seed = 1L, startTick = 0))
        assertThat((r as Result.Failure).error).isEqualTo(CasterInterrupted("t-long", "silence"))
    }
}
