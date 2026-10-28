package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.skillengine.runtime.CastId
import com.astarworks.astera.core.application.skillengine.runtime.OpContext
import com.astarworks.astera.core.application.skillengine.runtime.OpExecutor
import com.astarworks.astera.core.application.skillengine.runtime.ops.ISkillWorldEffectGateway
import com.astarworks.astera.core.application.skillengine.runtime.ops.SkillCastScope
import com.astarworks.astera.core.application.skillengine.runtime.ops.damageExecutors
import com.astarworks.astera.core.application.skillengine.runtime.ops.spawnExecutors
import com.astarworks.astera.core.application.skillengine.runtime.port.BlockHit
import com.astarworks.astera.core.application.skillengine.runtime.port.EntityRoot
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillEntityQuery
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillGameplayGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillParticleGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillSoundGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVictimAnimGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVisualGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.SkillDamage
import com.astarworks.astera.core.application.skillengine.runtime.port.SkillRuntimePorts
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.frame.FrameError
import com.astarworks.astera.core.domain.skillengine.frame.FrameId
import com.astarworks.astera.core.domain.skillengine.frame.FrameKind
import com.astarworks.astera.core.domain.skillengine.frame.FrameNode
import com.astarworks.astera.core.domain.skillengine.frame.FramePoint
import com.astarworks.astera.core.domain.skillengine.frame.FrameTree
import com.astarworks.astera.core.domain.skillengine.hit.ContactEvent
import com.astarworks.astera.core.domain.skillengine.hit.ContactResolver
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.hit.HitVolume
import com.astarworks.astera.core.domain.skillengine.hit.ResolvedVolume
import com.astarworks.astera.core.domain.skillengine.model.LocationSpec
import com.astarworks.astera.core.domain.skillengine.model.PointRef
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.model.ValueResolver
import com.astarworks.astera.core.domain.skillengine.model.literal
import com.astarworks.astera.core.domain.skillengine.motion.MotionSpec
import com.astarworks.astera.core.domain.skillengine.op.DamageComponentSpec
import com.astarworks.astera.core.domain.skillengine.op.DamageOp
import com.astarworks.astera.core.domain.skillengine.op.FrameOp
import com.astarworks.astera.core.domain.skillengine.op.HitOp
import com.astarworks.astera.core.domain.skillengine.op.SkillOp
import com.astarworks.astera.core.domain.skillengine.op.SpawnOp
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset.offset
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID

/**
 * Projectile and aoe ops of the skill engine v2 (ADR-0301): the real spawn / damage executors run
 * against a tiny in-scenario cast (frame tree, event bus, fly stepper, sphere narrow phase) and
 * fake outbound ports. Checks: lowering to place + fly + hit, gravity/drag arc, block -> landing aoe,
 * pierce count, aoe hit set.
 */
class ProjectileAoeScenarioTest {

    private class Victim(val id: UUID, val feet: Vec3) {
        val box = HitBox(Vec3(feet.x - 0.3, feet.y, feet.z - 0.3), Vec3(feet.x + 0.3, feet.y + 1.8, feet.z + 0.3))
    }

    private class Flight(val frame: FrameId, val spec: MotionSpec.Fly, var velocity: Vec3, var age: Int = 0)

    private class Cast(victims: List<Victim>) : OpContext, SkillCastScope {
        override val host: com.astarworks.astera.core.application.skillengine.runtime.OpHost get() = error("this scenario never reaches the cast host")
        val casterId: UUID = UUID.randomUUID()
        val victimList = victims
        val damaged = mutableListOf<Pair<UUID, Double>>()
        val lowered = mutableListOf<SkillOp>()

        override val cast = CastId(1)
        override val tree = Tree().also {
            it.add(FrameNode(FrameId("world"), FrameKind.WORLD, null, Frame(Vec3.ZERO)))
            it.add(FrameNode(FrameId("caster"), FrameKind.CASTER, FrameId("world"), Frame(Vec3.ZERO)))
        }
        override val values = object : ValueResolver {
            @Suppress("UNCHECKED_CAST")
            private fun <T> lit(v: Value<T>): T = (v as Value.Literal<T>).value
            override fun num(value: Value<Double>) = lit(value)
            override fun vec3(value: Value<Vec3>) = lit(value)
            override fun quat(value: Value<Quaternion>) = lit(value)
            override fun bool(value: Value<Boolean>) = lit(value)
            override fun text(value: Value<String>) = lit(value)
        }
        override val ports = SkillRuntimePorts(
            visuals = unused(), particles = unused<ISkillParticleGateway>(), sounds = unused<ISkillSoundGateway>(),
            victimAnim = unused<ISkillVictimAnimGateway>(),
            gameplay = object : ISkillGameplayGateway {
    override fun statusStacks(entity: UUID, statusId: String): Int = 0
            override fun removeStatus(victim: UUID, statusId: String) = Unit
            override fun cleanse(victim: UUID, negativeOnly: Boolean) = Unit
            override fun ignite(caster: UUID, victim: UUID, durationTicks: Int) = Unit
            override fun silence(caster: UUID, victim: UUID, durationTicks: Int) = Unit
            override fun modifyAttribute(caster: UUID, victim: UUID, attribute: String, operation: String, amount: Double, durationTicks: Int?) = Unit
            override fun restoreResource(victim: UUID, resource: String, amount: Double) = Unit
                override fun damage(caster: UUID, victim: UUID, components: List<SkillDamage>, hitPoint: Vec3) {
                    damaged += victim to components.sumOf { it.amount }
                }
                override fun applyStatus(caster: UUID, victim: UUID, statusId: String, stacks: Int, durationTicks: Int) = Unit
                override fun impulse(victim: UUID, vector: Vec3) = Unit
                override fun freezeEntities(entities: List<UUID>, ticks: Int) = Unit
            },
            entities = object : ISkillEntityQuery {
    override fun healthRatio(entity: UUID): Double = error("hp_ratio: this fake has no entity $entity")
                override fun nearby(point: Vec3, radius: Double, excluding: UUID?) =
                    victimList.filter { it.id != excluding && it.box.center.distance(point) <= radius + 1.0 }
                        .map { HitCandidate(it.id, it.feet, 0.0, it.box) }
                override fun rootOf(entity: UUID) =
                    victimList.firstOrNull { it.id == entity }?.let { EntityRoot(it.feet, 0.0, 0.0, 1.6, 1.8) }
                override fun raycastBlock(from: Vec3, to: Vec3): BlockHit? = null
            },
        )

        private val executors: Map<Any, OpExecutor<*>> =
            (spawnExecutors() + damageExecutors()).associateBy { it.opClass }

        // SkillCastScope
        override val caster: UUID get() = casterId
        override val skillId: String get() = "sim"
        override var tick: Int = 0
        override var contact: ContactEvent? = null
        override val contacts = object : ContactResolver {
            override fun narrow(
                hitId: String, tick: Int, hitterFrame: String, volume: ResolvedVolume,
                hitterVelocity: Vec3, candidates: List<HitCandidate>,
            ): List<ContactEvent> {
                val sphere = volume as ResolvedVolume.Sphere
                return candidates.filter { sphere.center.distance(it.box.center) <= sphere.radius }.map {
                    ContactEvent(hitId, tick, it.entityId, hitterFrame, null, sphere.center, Vec3(0.0, 1.0, 0.0), Vec3.ZERO, Vec3(0.0, 1.0, 0.0), Vec3.ZERO)
                }
            }
        }
        override val effects = object : ISkillWorldEffectGateway {
            override fun heal(caster: UUID, victim: UUID, amount: Double) = Unit
            override fun resurrect(caster: UUID, victim: UUID, healthFraction: Double) = Unit
            override fun explode(caster: UUID, at: Vec3, power: Double, breakBlocks: Boolean, incendiary: Boolean) = Unit
            override fun summon(caster: UUID, at: Vec3, entityType: String, count: Int, lifetimeTicks: Int) = Unit
            override fun lightning(caster: UUID, at: Vec3, visualOnly: Boolean) = Unit
        }

        override fun isAlly(entity: UUID) = false
        override fun <T> withContact(contact: ContactEvent, block: () -> T): T {
            val saved = this.contact
            this.contact = contact
            try { return block() } finally { this.contact = saved }
        }

        private val handlers = mutableListOf<Triple<String, String, () -> Unit>>()
        private val flights = mutableMapOf<String, Flight>()
        private val hits = mutableMapOf<String, Pair<FrameId, HitVolume.Sphere>>()
        private val hitOnce = mutableSetOf<Pair<String, UUID>>()
        val running: Boolean get() = flights.isNotEmpty() || hits.isNotEmpty()

        override fun onEvent(event: String, source: String, handler: () -> Unit) {
            handlers += Triple(event, source, handler)
        }

        override fun endProjectile(source: String, body: FrameId, keepBody: Boolean) {
            hits.remove(source)
            flights.remove(source)
        }

        override fun raise(event: String) = Unit

        override fun run(ops: List<SkillOp>) {
            for (op in ops) {
                lowered += op
                when (op) {
                    is FrameOp.Motion -> flights[op.id] = Flight(FrameId(op.frame), op.spec as MotionSpec.Fly, values.vec3((op.spec as MotionSpec.Fly).velocity))
                    is HitOp.Hit -> hits[op.id] = FrameId(op.frames.single()) to (op.volume as HitVolume.Sphere)
                    else -> @Suppress("UNCHECKED_CAST") (executors.getValue(op::class) as OpExecutor<SkillOp>).execute(op, this)
                }
            }
        }

        private fun fire(event: String, source: String, with: ContactEvent? = null) {
            for ((e, s, h) in handlers.filter { it.first == event && it.second == source }) {
                if (with != null) withContact(with, h) else h()
            }
        }

        /** Fly law of the old stepper: move one velocity step, then gravity, then drag; ground at y <= 0. */
        fun step() {
            tick++
            for ((source, flight) in flights.toMap()) {
                val node = tree.node(flight.frame)!!
                val pos = node.local.origin + flight.velocity
                tree.setLocal(flight.frame, Frame(pos, node.local.rotation))
                flight.velocity = Vec3(flight.velocity.x, flight.velocity.y - values.num(flight.spec.gravity), flight.velocity.z) *
                    (1.0 - values.num(flight.spec.drag))
                flight.age++
                if (pos.y <= 0.0) { fire("block", source); continue }
                val (frame, volume) = hits[source] ?: continue
                for (v in victimList) {
                    if (!hitOnce.add(source to v.id)) continue
                    if (!flights.containsKey(source)) break
                    val d = v.box.center.distance(pos)
                    if (d > values.num(volume.radius)) { hitOnce.remove(source to v.id); continue }
                    val c = ContactEvent(source, tick, v.id, frame.value, null, pos, Vec3(0.0, 1.0, 0.0), Vec3.ZERO, Vec3(0.0, 1.0, 0.0), flight.velocity)
                    fire("hit", source, c)
                }
                if (flights.containsKey(source) && flight.age >= values.num(flight.spec.maxTicks!!)) fire("expire", source)
            }
        }
    }

    private class Tree : FrameTree {
        private val nodes = mutableMapOf<FrameId, FrameNode>()
        override fun node(id: FrameId) = nodes[id]
        override fun children(id: FrameId) = nodes.values.filter { it.parent == id }.map { it.id }
        override fun add(node: FrameNode): Result<Unit, FrameError> { nodes[node.id] = node; return Result.Success(Unit) }
        override fun remove(id: FrameId) { nodes.remove(id) }
        override fun setLocal(id: FrameId, local: Frame): Result<Unit, FrameError> {
            val n = nodes[id] ?: return Result.Failure(FrameError.Unknown(id.value))
            nodes[id] = n.copy(local = local)
            return Result.Success(Unit)
        }
        override fun worldOf(id: FrameId): Result<Frame, FrameError> {
            val n = nodes[id] ?: return Result.Failure(FrameError.Unknown(id.value))
            val parent = n.parent ?: return Result.Success(n.local)
            return worldOf(parent).map { it.compose(n.local) }
        }
        override fun reparent(id: FrameId, newParent: FrameId, keepWorld: Boolean, snap: Frame?): Result<Unit, FrameError> {
            val n = nodes[id] ?: return Result.Failure(FrameError.Unknown(id.value))
            nodes[id] = n.copy(parent = newParent, local = snap ?: n.local)
            return Result.Success(Unit)
        }
        override fun definePoint(point: FramePoint) = Unit
        override fun point(node: FrameId, name: String): FramePoint? = null
        override fun pointWorld(node: FrameId, name: String?): Result<Vec3, FrameError> =
            worldOf(node).map { it.origin }
    }

    private companion object {
        inline fun <reified T : Any> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, _ ->
            throw UnsupportedOperationException("${m.name} is not used by this scenario")
        } as T

        fun damage10(id: String) = DamageOp.Damage(id, null, TargetRef.Victim, listOf(DamageComponentSpec("physical", literal(10.0))))

        fun projectile(
            gravity: Double, drag: Double, pierce: Int, origin: Vec3,
            onHit: List<DamageOp> = emptyList(), onBlock: List<SpawnOp> = emptyList(),
        ) = SpawnOp.Projectile(
            id = "shot", whenExpr = null, body = "bolt",
            from = LocationSpec.AtValue(literal(origin)),
            velocity = literal(Vec3(0.0, 0.0, 1.0)), gravity = literal(gravity), drag = literal(drag),
            maxTicks = literal(40.0), volume = HitVolume.Sphere(PointRef("self"), literal(0.6)),
            pierce = pierce, onHit = onHit, onBlock = onBlock, onExpire = emptyList(),
        )
    }

    private val aoeAtLanding = SpawnOp.Aoe(
        id = "boom", whenExpr = null, at = LocationSpec.AtPoint(PointRef("bolt")),
        volume = HitVolume.Sphere(PointRef("self"), literal(3.0)), onHit = listOf(damage10("boom_dmg")),
    )

    @Test
    fun `projectile lowers to fly plus hit and follows the gravity arc until it lands`() {
        val near = Victim(UUID.randomUUID(), Vec3(0.0, 0.0, 10.0))
        val far = Victim(UUID.randomUUID(), Vec3(0.0, 0.0, 16.0))
        val cast = Cast(listOf(near, far))
        spawnExecutors().first { it.opClass == SpawnOp.Projectile::class }.let {
            @Suppress("UNCHECKED_CAST")
            (it as OpExecutor<SpawnOp.Projectile>).execute(projectile(0.1, 0.0, 0, Vec3(0.0, 3.0, 0.0), onBlock = listOf(aoeAtLanding)), cast)
        }

        val fly = cast.lowered.filterIsInstance<FrameOp.Motion>().single()
        assertThat((fly.spec as MotionSpec.Fly).gravity).isEqualTo(literal(0.1))
        assertThat(cast.lowered.filterIsInstance<HitOp.Hit>().single().frames).containsExactly("bolt")

        // y_n = 3 - 0.05 * (n - 1) * n; the first n with y <= 0 is 9 (y = -0.6), z = n.
        var landedAt = -1
        for (n in 1..20) {
            cast.step()
            val body = cast.tree.worldOf(FrameId("bolt")).getOrNull()!!.origin
            if (landedAt < 0) {
                assertThat(body.y).isCloseTo(3.0 - 0.05 * (n - 1) * n, offset(1e-9))
                assertThat(body.z).isCloseTo(n.toDouble(), offset(1e-9))
            }
            if (!cast.running && landedAt < 0) landedAt = n
        }
        assertThat(landedAt).isEqualTo(9)
        assertThat(cast.damaged.map { it.first }).containsExactly(near.id)
    }

    @Test
    fun `pierce lets a projectile pass through n victims and no more`() {
        for ((pierce, expected) in listOf(0 to 1, 1 to 2, 2 to 3)) {
            val line = (1..4).map { Victim(UUID.randomUUID(), Vec3(0.0, 0.0, 3.0 * it)) }
            val cast = Cast(line)
            @Suppress("UNCHECKED_CAST")
            (spawnExecutors().first { it.opClass == SpawnOp.Projectile::class } as OpExecutor<SpawnOp.Projectile>)
                .execute(projectile(0.0, 0.0, pierce, Vec3(0.0, 1.0, 0.0), onHit = listOf(damage10("hit_dmg"))), cast)
            repeat(40) { cast.step() }
            assertThat(cast.damaged.map { it.first }).containsExactlyElementsOf(line.take(expected).map { it.id })
            assertThat(cast.running).isFalse()
        }
    }

    @Test
    fun `aoe hits exactly the victims inside the volume once each`() {
        val inner = Victim(UUID.randomUUID(), Vec3(1.0, 0.0, 0.0))
        val mid = Victim(UUID.randomUUID(), Vec3(0.0, 0.0, 2.0))
        val outer = Victim(UUID.randomUUID(), Vec3(4.0, 0.0, 0.0))
        val cast = Cast(listOf(inner, mid, outer))
        val aoe = aoeAtLanding.copy(at = LocationSpec.AtValue(literal(Vec3(0.0, 0.0, 0.0))))
        @Suppress("UNCHECKED_CAST")
        (spawnExecutors().first { it.opClass == SpawnOp.Aoe::class } as OpExecutor<SpawnOp.Aoe>).execute(aoe, cast)

        assertThat(cast.damaged.map { it.first }).containsExactlyInAnyOrder(inner.id, mid.id)
        assertThat(cast.damaged.map { it.second }).containsOnly(10.0)
        assertThat(cast.tree.node(FrameId("boom#aoe"))).isNull()
    }
}
