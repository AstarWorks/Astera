package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.skillengine.load.SkillCatalog
import com.astarworks.astera.core.application.skillengine.runtime.CastId
import com.astarworks.astera.core.application.skillengine.runtime.CastRequest
import com.astarworks.astera.core.application.skillengine.runtime.SkillEngineContents
import com.astarworks.astera.core.application.skillengine.runtime.SkillEngineGateways
import com.astarworks.astera.core.application.skillengine.runtime.SkillEngineKit
import com.astarworks.astera.core.application.skillengine.runtime.ops.ISkillWorldEffectGateway
import com.astarworks.astera.core.domain.skillengine.world.BlockCell
import com.astarworks.astera.core.application.skillengine.runtime.port.BlockDamageOutcome
import com.astarworks.astera.core.application.skillengine.runtime.port.BlockHit
import com.astarworks.astera.core.application.skillengine.runtime.port.EntityRoot
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillCooldownGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillEntityQuery
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillGameplayGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillItemGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillMovementGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillParticleGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillSoundGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVictimAnimGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVisualGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillWorldGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ParticleBurst
import com.astarworks.astera.core.application.skillengine.runtime.port.SkillDamage
import com.astarworks.astera.core.application.skillengine.runtime.port.SkillRuntimePorts
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualHandle
import com.astarworks.astera.core.application.skillengine.runtime.presentation.ISkillCasterAnimGateway
import com.astarworks.astera.core.application.skillengine.runtime.presentation.ISkillScreenGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.op.PlateFace
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.UUID

/**
 * SKL-09: one real converted v1 skill per ported grammar gap, run by the real cast runner (decoder, compiler, CEL,
 * op executors via `SkillEngineKit`); only the ports are fakes. The skills under `src/test/resources/ported-skills`
 * are the converter's output for the named v1 skills.
 */
class PortedGrammarScenarioTest {

    private val caster: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c0a1")
    private fun victim(n: Int): UUID = UUID.fromString("00000000-0000-0000-0000-0000000d%04d".format(n))

    private class Dealt(val tick: Int, val victim: UUID, val amount: Double, val ignoreInvulnerability: Boolean)

    private inner class Sim(val victims: Map<UUID, Vec3>, val casterStacks: Map<String, Int> = emptyMap(), val blockAtZ: Double? = null) {
        var now = 0
        val dealt = ArrayList<Dealt>()
        val sounds = ArrayList<Pair<Int, String>>()
        val removed = ArrayList<Pair<UUID, String>>()
        val blockStrikes = ArrayList<Triple<Int, Int, String?>>()

        private inline fun <reified T> noop(): T = java.lang.reflect.Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, _ ->
            when (m.returnType) {
                java.lang.Boolean.TYPE -> true
                Integer.TYPE -> 0
                java.lang.Double.TYPE -> 0.0
                java.lang.Long.TYPE -> 0L
                else -> if (m.returnType == VisualHandle::class.java) VisualHandle(1) else null
            }
        } as T

        private val gameplay = object : ISkillGameplayGateway by noop() {
            override fun damage(caster: UUID, victim: UUID, components: List<SkillDamage>, hitPoint: Vec3) =
                damage(caster, victim, components, hitPoint, false)
            override fun damage(caster: UUID, victim: UUID, components: List<SkillDamage>, hitPoint: Vec3, ignoreInvulnerability: Boolean) {
                for (c in components) dealt += Dealt(now, victim, c.amount, ignoreInvulnerability)
            }
            override fun statusStacks(entity: UUID, statusId: String): Int = if (entity == caster) casterStacks[statusId] ?: 0 else 0
            override fun removeStatus(victim: UUID, statusId: String) { removed += victim to statusId }
        }

        private val sound = object : ISkillSoundGateway {
            override fun play(sound: String, at: Vec3, volume: Double, pitch: Double) { sounds += now to sound }
        }

        private val entities = object : ISkillEntityQuery {
            private fun box(feet: Vec3) = HitBox(Vec3(feet.x - 0.3, 0.0, feet.z - 0.3), Vec3(feet.x + 0.3, 1.8, feet.z + 0.3))
            override fun nearby(point: Vec3, radius: Double, excluding: UUID?): List<HitCandidate> =
                victims.filter { (id, feet) -> id != excluding && box(feet).center.distance(point) <= radius + 1.2 }
                    .map { (id, feet) -> HitCandidate(id, feet, 0.0, box(feet)) }
            override fun rootOf(entity: UUID): EntityRoot? =
                if (entity == caster) EntityRoot(Vec3.ZERO, 0.0, 0.0, 1.62, 1.8) else victims[entity]?.let { EntityRoot(it, 0.0, 0.0, 1.62, 1.8) }
            override fun raycastBlock(from: Vec3, to: Vec3): BlockHit? {
                val z = blockAtZ ?: return null
                return if (from.z <= z && to.z >= z) BlockHit(Vec3(0.0, 1.0, z), Vec3(0.0, 0.0, -1.0), "minecraft:stone") else null
            }
        }

        private val world = object : ISkillWorldGateway by noop() {
            override fun blockAt(cell: BlockCell): String? = "minecraft:stone"
            override fun canEdit(cast: CastId, cell: BlockCell, outsideArena: Boolean) = true
            override fun isCover(cell: BlockCell) = false
            override fun damage(cast: CastId, cell: BlockCell, blockDamage: Int, toolClass: String?): BlockDamageOutcome {
                blockStrikes += Triple(now, blockDamage, toolClass)
                return BlockDamageOutcome.CHIPPED
            }
        }

        private val c = Coin2CastWiring.collaborators()
        val catalog: SkillCatalog = c.newCatalog().also {
            val dir = Path.of(requireNotNull(PortedGrammarScenarioTest::class.java.getResource("/ported-skills")).toURI())
            val report = SkillEngineContents(it).reload(dir)
            assertThat(report.failed).describedAs("converted skills load: ${report.failures}").isZero()
        }

        val kit: SkillEngineKit = SkillEngineKit.build(
            gateways = SkillEngineGateways(
                ports = SkillRuntimePorts(noop<ISkillVisualGateway>(), noop<ISkillParticleGateway>(), sound, gameplay, entities, noop<ISkillVictimAnimGateway>()),
                movement = noop<ISkillMovementGateway>(),
                world = world,
                casterAnim = noop<ISkillCasterAnimGateway>(),
                screen = noop<ISkillScreenGateway>(),
                cooldowns = noop<ISkillCooldownGateway>(),
                items = noop<ISkillItemGateway>(),
                effects = noop<ISkillWorldEffectGateway>(),
            ),
            newTree = c.newTree, motion = c.motion, contacts = c.contacts, exprs = c.exprs, points = c.points,
            skills = catalog::get, observer = null,
        )

        fun cast(id: String, ticks: Int): CastId {
            val skill = requireNotNull(catalog.get(id)) { "skill $id not loaded" }
            val req = CastRequest(skill, caster, Vec3.ZERO, 0.0, 0.0, seed = 7L, startTick = 0)
            val cast = when (val r = kit.runner.start(req)) {
                is Result.Success -> r.value
                is Result.Failure -> error("cast did not start: ${r.error}")
            }
            while (now < ticks) {
                kit.runner.tick(now)
                kit.world.tick()
                now++
            }
            assertThat(kit.runner.diagnostics()).describedAs("no op failed").isEmpty()
            return cast
        }
    }

    @Test
    fun `header meta is decoded onto the compiled skill`() {
        val sim = Sim(emptyMap())
        val meta = sim.catalog.get("bastion-greatsword-s2")!!.def.meta
        assertThat(meta.cooldownTicks).isEqualTo(100)
        assertThat(meta.resourceCost).isEqualTo(30)
        assertThat(meta.name["en"]).isEqualTo("Judgement Rush")
        assertThat(meta.tags).contains("charge")
    }

    @Test
    fun `delay op and ignore_invulnerability - orrery s3 lands four delayed hits through i-frames`() {
        val sim = Sim(mapOf(victim(1) to Vec3(0.0, 0.0, 6.0)))
        sim.cast("orrery-s3", 200)
        assertThat(sim.dealt.map { it.tick }).describedAs("delays 45/60/72/143 after the beam hit at tick 0").containsExactly(45, 60, 72, 143)
        assertThat(sim.dealt).allMatch { it.ignoreInvulnerability }
    }

    @Test
    fun `max_count - edict s1 sector hit takes only the nearest victim`() {
        val near = victim(1)
        val far = victim(2)
        val sim = Sim(mapOf(near to Vec3(0.0, 0.0, 1.5), far to Vec3(0.0, 0.0, 2.5)))
        sim.cast("edict-s1", 40)
        assertThat(sim.dealt.map { it.victim }.toSet()).describedAs("hit cap of 1 spares the far victim").containsExactly(near)
        assertThat(sim.dealt.map { it.amount }).containsExactly(40.0, 55.0)
    }

    @Test
    fun `per_stack - bastion s2 adds half a point per charge stack and the last blow consumes it`() {
        val sim = Sim(mapOf(victim(1) to Vec3(0.0, 0.0, 2.0)), casterStacks = mapOf("charge" to 10))
        sim.cast("bastion-greatsword-s2", 80)
        assertThat(sim.dealt.map { it.amount }).containsExactly(40.0, 45.0, 65.0)
        assertThat(sim.removed).containsExactly(caster to "charge")
    }

    @Test
    fun `explode at a point - fireball bursting on a block hurts the victim beside the point`() {
        val v = victim(1)
        val sim = Sim(mapOf(v to Vec3(0.0, 0.0, 6.0)), blockAtZ = 4.5)
        sim.cast("fireball-cast", 60)
        assertThat(sim.dealt.map { it.victim to it.amount }).containsExactly(v to 27.5)
    }

    @Test
    fun `block_damage - flame burst chips blocks at the impact`() {
        val sim = Sim(mapOf(victim(1) to Vec3(0.0, 0.0, 6.0)))
        sim.cast("flame-burst", 80)
        assertThat(sim.dealt).describedAs("bolt hit the victim").isNotEmpty()
        assertThat(sim.blockStrikes).describedAs("break_area chip at the contact").isNotEmpty()
        assertThat(sim.blockStrikes.map { it.second }.toSet()).containsExactly(35)
    }

    @Test
    fun `melee miss - sword slash plays the miss sound only when nothing was hit`() {
        val missed = Sim(emptyMap())
        missed.cast("sword-slash", 20)
        assertThat(missed.sounds.map { it.second }).contains("astera:weapons/blade-miss")

        val hit = Sim(mapOf(victim(1) to Vec3(0.0, 0.0, 2.0)))
        hit.cast("sword-slash", 20)
        assertThat(hit.dealt).isNotEmpty()
        assertThat(hit.sounds.map { it.second }).doesNotContain("astera:weapons/blade-miss")
    }

    @Test
    fun `snapshot_pose - verdict hammer ego strikes through a frozen caster pose frame`() {
        val sim = Sim(mapOf(victim(1) to Vec3(0.0, 0.0, 2.0)))
        sim.cast("verdict-hammer-ego", 230)
        assertThat(sim.dealt).describedAs("the snapshot-framed sphere hits landed").isNotEmpty()
    }
}
