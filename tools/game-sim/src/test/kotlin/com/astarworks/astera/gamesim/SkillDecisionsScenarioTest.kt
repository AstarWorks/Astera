package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.adapter.paper.skillengine.PaperSkillParticleGateway
import com.astarworks.astera.core.application.contents.ParticleContents
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillParticleGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ParticleBurst
import com.astarworks.astera.core.domain.effect.ParticleId
import com.astarworks.astera.core.domain.effect.ParticleSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * SKL-15 design decisions (founder 2026-10-28), pinned through the real cast runner: parry negation, feet distance,
 * exact repeat ticks, victim hit-stop, the vanilla emit mapping, the pitch-aware sector and muzzle, the E.G.O windup lock.
 */
class SkillDecisionsScenarioTest {

    private val attacker = simVictim(1)

    private fun flat(yaml: String) = yaml.lines().joinToString(" ") { it.trim() }

    private fun skill(id: String, gameplay: String = "{timeline: [{id: t0, at: 0, do: []}]}", presentation: String = "{timeline: []}", frames: String = "[]"): Pair<String, String> =
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

    // ---- row 1: parry negates the parried hit ----

    @Test
    fun `parry negates the hit it answers and still ripostes`() {
        val sim = CastSim(victims = mapOf(attacker to Vec3(0.0, 0.0, 2.0)))
        sim.start("couture-rapier-def")
        sim.step(5)
        assertThat(sim.incoming(attacker, 12.0)).describedAs("absorb: true takes the parried hit to 0").isEqualTo(0.0)
        sim.step(30)
        assertThat(sim.dealt.filter { it.victim == attacker }).describedAs("riposte").hasSize(1)
    }

    // ---- row 2 + 12: feet-to-feet distance, and the bastion-greatsword-s1 charge gate (enemies only) ----

    @Test
    fun `distance gate measures feet to feet, not to the hitbox surface`() {
        val gate = skill(
            "t-feet",
            gameplay = """{timeline: [{id: t0, at: 0, do: [{id: g, op: status, when: "= nearest_enemy_dist(caster) < 3.6", status: charge, target: caster, stacks: 1, duration_ticks: 10}]}]}""",
        )
        // halfWidth 0.8: the box surface is 0.8 nearer than the feet; a surface measure would let 3.7 through.
        val near = CastSim(victims = mapOf(attacker to Vec3(0.0, 0.0, 3.5)), extraSkills = mapOf(gate))
        near.cast("t-feet", 3)
        assertThat(near.statuses.filter { it.third == "charge" }).describedAs("feet at 3.5 < 3.6").hasSize(1)
        val far = CastSim(victims = mapOf(attacker to Vec3(0.0, 0.0, 3.7)), extraSkills = mapOf(gate))
        far.cast("t-feet", 3)
        assertThat(far.statuses.filter { it.third == "charge" }).describedAs("feet at 3.7 >= 3.6").isEmpty()
    }

    @Test
    fun `bastion s1 charge is granted only with an enemy within 3_6 feet to feet`() {
        val near = CastSim(victims = mapOf(attacker to Vec3(0.0, 0.0, 3.0)))
        near.cast("bastion-greatsword-s1", 45)
        assertThat(near.statuses.filter { it.third == "charge" }).hasSize(1)
        val far = CastSim(victims = mapOf(attacker to Vec3(0.0, 0.0, 4.5)))
        far.cast("bastion-greatsword-s1", 45)
        assertThat(far.statuses.filter { it.third == "charge" }).isEmpty()
    }

    // ---- row 3: exact repeat ticks ----

    @Test
    fun `repeating sounds and emits fire at exactly at, at+N, at+2N`() {
        val rep = skill(
            "t-repeat",
            gameplay = "{timeline: [{id: t0, at: 0, do: []}, {id: t40, at: 40, do: []}]}",
            presentation = """
                {timeline: [
                  {id: s, at: 3, do: [{id: snd, op: sound, sound: "astera:test/tick", at: caster, every: 5, until: end}]},
                  {id: e, at: 3, every: 5, until: end, do: [{id: em, op: emit, at: caster, particle: end_rod}]}
                ]}
            """.trimIndent(),
        )
        val bursts = ArrayList<Pair<Int, ParticleBurst>>()
        lateinit var sim: CastSim
        val gateway = object : ISkillParticleGateway { override fun emit(burst: ParticleBurst) { bursts += sim.now to burst } }
        sim = CastSim(victims = emptyMap(), extraSkills = mapOf(rep), particleGateway = gateway)
        sim.cast("t-repeat", 30)
        assertThat(sim.sounds.filter { it.second == "astera:test/tick" }.map { it.first }.take(6)).containsExactly(3, 8, 13, 18, 23, 28)
        assertThat(bursts.map { it.first }.take(6)).containsExactly(3, 8, 13, 18, 23, 28)
    }

    // ---- row 4: a hit handler's `enemy` scope freezes the struck entity too ----

    @Test
    fun `freeze scope enemy freezes the struck victim and the caster`() {
        val sim = CastSim(victims = mapOf(attacker to Vec3(0.0, 0.0, 2.0)))
        sim.cast("bastion-greatsword-s1", 45)
        val hitStop = sim.frozen.first()
        assertThat(hitStop.second).describedAs("victim and caster both held").contains(attacker)
        assertThat(hitStop.second).hasSize(2)
        assertThat(hitStop.third).isEqualTo(2)
    }

    // ---- rows 5-7: emit is the vanilla spawnParticle ----

    private fun emitBursts(emit: String, pitchDeg: Double = 0.0): List<ParticleBurst> {
        val s = skill(
            "t-emit",
            gameplay = "{timeline: [{id: t0, at: 0, do: []}]}",
            presentation = "{timeline: [{id: e, at: 0, do: [{id: em, op: emit, at: caster, $emit}]}]}",
        )
        val out = ArrayList<ParticleBurst>()
        CastSim(victims = emptyMap(), extraSkills = mapOf(s), pitchDeg = pitchDeg, particleGateway = object : ISkillParticleGateway { override fun emit(burst: ParticleBurst) { out += burst } }).cast("t-emit", 2)
        return out
    }

    @Test
    fun `a bare vanilla emit is the vanilla defaults, with no hidden velocity or spread`() {
        val b = emitBursts("particle: end_rod").single()
        assertThat(b.particle).isEqualTo("end_rod")
        assertThat(b.count).describedAs("unset = vanilla 1 (null here)").isNull()
        assertThat(b.offset).isEqualTo(Vec3.ZERO)
        assertThat(b.speed).isEqualTo(0.0)
        assertThat(b.dir).describedAs("no dir sugar").isNull()
        assertThat(b.colorHex).isNull()
        assertThat(b.dataSize).isNull()
    }

    @Test
    fun `emit count offset speed and data reach the gateway untouched, count 0 included`() {
        val dust = emitBursts("""particle: dust, count: 3, offset: [0.1, 0.2, 0.3], speed: 0.0, data: "#ff8800", data_size: 0.5""").single()
        assertThat(dust.count).isEqualTo(3)
        assertThat(dust.offset).isEqualTo(Vec3(0.1, 0.2, 0.3))
        assertThat(dust.speed).isEqualTo(0.0)
        assertThat(dust.colorHex).isEqualTo("#ff8800")
        assertThat(dust.dataSize).isEqualTo(0.5)
        val directional = emitBursts("particle: flame, count: 0, offset: [0.0, 1.0, 0.0], speed: 0.5").single()
        assertThat(directional.count).describedAs("vanilla count 0 stays 0").isEqualTo(0)
        assertThat(directional.offset).isEqualTo(Vec3(0.0, 1.0, 0.0))
        assertThat(directional.speed).isEqualTo(0.5)
        assertThat(directional.dir).isNull()
    }

    @Test
    fun `dir is only sugar and a custom shape without dir faces the full caster look`() {
        val sugar = emitBursts("""particle: flame, count: 4, dir: "= look(caster)", speed: 0.2""").single()
        assertThat(sugar.dir).isEqualTo(Vec3(0.0, 0.0, 1.0))
        assertThat(sugar.count).isEqualTo(4)
        val shaped = emitBursts("particle: gun-muzzle-flash", pitchDeg = -90.0).single()
        assertThat(shaped.dir).isNull()
        assertThat(shaped.look.y).describedAs("look(caster) incl. pitch").isCloseTo(1.0, Offset.offset(1e-9))
    }

    private class RecordingWorld : IWorldGateway by decisionsNoopWorld() {
        val particles = ArrayList<ParticleSpec>()
        override fun spawnParticle(world: WorldId, at: Vec3, particle: ParticleSpec) { particles += particle }
    }

    private fun paperEmit(emit: String, pitchDeg: Double = 0.0): List<ParticleSpec> {
        val contentDir = Path.of(requireNotNull(System.getProperty("astera.sim.contentDir")))
        val registry = ParticleContents().also { check(it.bootstrap(contentDir.resolve("particles")).failed == 0) }
        val world = RecordingWorld()
        val gateway = PaperSkillParticleGateway(world, { WorldId("world") }) { registry.find(ParticleId(it)) }
        val s = skill(
            "t-emit",
            presentation = "{timeline: [{id: e, at: 0, do: [{id: em, op: emit, at: caster, $emit}]}]}",
        )
        CastSim(victims = emptyMap(), extraSkills = mapOf(s), pitchDeg = pitchDeg, particleGateway = gateway).cast("t-emit", 2)
        return world.particles
    }

    @Test
    fun `the Paper gateway spawns exactly the vanilla arguments, and a custom count is the number of particles`() {
        val dust = paperEmit("""particle: dust, count: 3, offset: [0.1, 0.2, 0.3], speed: 0.0, data: "#ff8800", data_size: 0.5""").single()
        assertThat(dust.key).isEqualTo("dust")
        assertThat(dust.count).isEqualTo(3)
        assertThat(dust.offset).isEqualTo(Vec3(0.1, 0.2, 0.3))
        assertThat(dust.extra).isEqualTo(0.0)
        assertThat(dust.scale).isEqualTo(0.5f)
        val bare = paperEmit("particle: end_rod").single()
        assertThat(bare.count).isEqualTo(1)
        assertThat(bare.offset).isEqualTo(Vec3.ZERO)
        assertThat(bare.extra).isEqualTo(0.0)
        assertThat(paperEmit("particle: gun-muzzle-flash").sumOf { it.count }).describedAs("definition's own count (5)").isEqualTo(5)
        assertThat(paperEmit("particle: gun-muzzle-flash, count: 2").sumOf { it.count }).describedAs("count = number of particles").isEqualTo(2)
    }

    // ---- row 8: pitch-aware melee sector ----

    private val sector = skill(
        "t-sector",
        gameplay = """
            {timeline: [{id: t0, at: 0, do: [{id: sw, op: hit, frames: [caster], window: once,
              sector: {frame: caster, at: eye, forward: caster.forward, left: caster.left, angle_deg: 90, range: 4.0, height: 1.0}}]}],
             "on": {hit: [{id: dmg, op: damage, components: [{kind: physical, amount: 10}], target: victim}]}}
        """.trimIndent(),
    )

    @Test
    fun `a melee sector from the eye follows the look pitch`() {
        val above = mapOf(attacker to Vec3(0.0, 3.0, 2.12))
        val up = CastSim(victims = above, pitchDeg = -45.0, extraSkills = mapOf(sector))
        up.cast("t-sector", 3)
        assertThat(up.dealt).describedAs("aimed up at a target above").hasSize(1)
        val level = CastSim(victims = above, pitchDeg = 0.0, extraSkills = mapOf(sector))
        level.cast("t-sector", 3)
        assertThat(level.dealt).describedAs("same target, level aim: outside the slab").isEmpty()
    }

    // ---- row 9: pitch-aware projectile muzzle ----

    @Test
    fun `a muzzle on the aim frame sits at eye plus the full look`() {
        val muzzle = skill(
            "t-muzzle",
            gameplay = """
                {timeline: [{id: t0, at: 0, do: [{id: shot, op: projectile, body: bolt, from: pt_muzzle, velocity: "= look(caster) * 0.01", max_ticks: 3, radius: 0.1}]}],
                 "on": {hit: [{id: dmg, op: damage, components: [{kind: physical, amount: 10}], target: victim}]}}
            """.trimIndent(),
            frames = "[{id: bolt, kind: body, parent: world, inactive: true}, {id: pt_muzzle, kind: point, parent: aim, offset: [0.0, 0.0, 2.0]}]",
        )
        // looking 60 degrees up: eye (0,1.62,0) + look * 2 = (0, 3.35, 1.0)
        val steep = CastSim(victims = mapOf(attacker to Vec3(0.0, 2.6, 1.0)), pitchDeg = -60.0, extraSkills = mapOf(muzzle))
        steep.cast("t-muzzle", 5)
        assertThat(steep.dealt).describedAs("bolt starts at the pitched muzzle").hasSize(1)
        val flat = CastSim(victims = mapOf(attacker to Vec3(0.0, 2.6, 1.0)), pitchDeg = 0.0, extraSkills = mapOf(muzzle))
        flat.cast("t-muzzle", 5)
        assertThat(flat.dealt).describedAs("level aim puts the muzzle at (0,1.62,2)").isEmpty()
    }

    // ---- row 13: E.G.O windup lock ----

    @Test
    fun `bastion greatsword ego roots the caster for the 20 tick windup`() {
        val sim = CastSim(victims = mapOf(attacker to Vec3(0.0, 0.0, 2.5)))
        sim.cast("bastion-greatsword-ego", 40)
        assertThat(sim.moveLocks.first()).describedAs("locked at cast start, speed 0").isEqualTo(0 to 0.0)
    }
}

private fun decisionsNoopWorld(): IWorldGateway = java.lang.reflect.Proxy.newProxyInstance(
    IWorldGateway::class.java.classLoader, arrayOf(IWorldGateway::class.java),
) { _, m, _ ->
    when (m.returnType) {
        java.lang.Boolean.TYPE -> false
        Integer.TYPE -> 0
        java.lang.Double.TYPE -> 0.0
        java.lang.Long.TYPE -> 0L
        else -> null
    }
} as IWorldGateway
