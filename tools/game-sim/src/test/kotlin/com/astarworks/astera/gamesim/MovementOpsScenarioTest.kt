package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.skillengine.load.OpReader
import com.astarworks.astera.core.application.skillengine.load.ops.movementDecoders
import com.astarworks.astera.core.application.skillengine.runtime.CastId
import com.astarworks.astera.core.application.skillengine.runtime.OpContext
import com.astarworks.astera.core.application.skillengine.runtime.OpExecutor
import com.astarworks.astera.core.application.skillengine.runtime.ops.DashStrikeDriver
import com.astarworks.astera.core.application.skillengine.runtime.ops.LeapArc
import com.astarworks.astera.core.application.skillengine.runtime.ops.MovementHost
import com.astarworks.astera.core.application.skillengine.runtime.ops.movementExecutors
import com.astarworks.astera.core.application.skillengine.runtime.port.BlockHit
import com.astarworks.astera.core.application.skillengine.runtime.port.EntityRoot
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillEntityQuery
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillGameplayGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillMovementGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillParticleGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillSoundGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVictimAnimGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualPose
import com.astarworks.astera.core.application.skillengine.runtime.port.SpriteInfo
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVisualGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ParticleBurst
import com.astarworks.astera.core.application.skillengine.runtime.port.SkillDamage
import com.astarworks.astera.core.application.skillengine.runtime.port.SkillRuntimePorts
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualHandle
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.frame.FrameTree
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.model.LocationSpec
import com.astarworks.astera.core.domain.skillengine.model.PointRef
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.model.ValueResolver
import com.astarworks.astera.core.domain.skillengine.model.literal
import com.astarworks.astera.core.domain.skillengine.op.MovementOp
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.op.SkillOp
import com.astarworks.astera.core.domain.skillengine.op.PlateFace
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset.offset
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.math.sqrt

/**
 * Movement op family (SKL-08 W12): real executors, decoder and DashStrikeDriver against fake outbound
 * ports. Pins the old DashStrike semantics: hit once, invulnerable during the dash, stop before the
 * target, direction in caster-local axes, block stop.
 */
class MovementOpsScenarioTest {

    private val caster = UUID(0, 1)
    private val enemyA = UUID(0, 2)
    private val enemyB = UUID(0, 3)

    private class World {
        val feet = HashMap<UUID, Vec3>()
        val alive = HashSet<UUID>()
        var casterYaw = 0.0
        var solidFromZ: Double? = null
        val velocities = HashMap<UUID, Vec3>()
        val added = ArrayList<Pair<UUID, Vec3>>()
        val impulses = ArrayList<Pair<UUID, Vec3>>()
        val teleports = ArrayList<Vec3>()
        var immune = false
        val immuneTrace = ArrayList<Boolean>()
        val hits = ArrayList<UUID>()
        var victim: UUID? = null
    }

    private fun box(feet: Vec3) = HitBox(feet + Vec3(-0.3, 0.0, -0.3), feet + Vec3(0.3, 1.8, 0.3))

    private class Env(val world: World, val ports: SkillRuntimePorts, val movement: ISkillMovementGateway, val host: MovementHost)

    private fun env(caster: UUID): Env {
        val w = World()
        val entities = object : ISkillEntityQuery {
            override fun nearby(point: Vec3, radius: Double, excluding: UUID?): List<HitCandidate> =
                w.feet.filter { (id, _) -> id != excluding && id in w.alive }
                    .map { (id, f) -> HitCandidate(id, f, 0.0, box(f)) }
                    .filter { it.box.center.distance(point) <= radius }

            override fun rootOf(entity: UUID): EntityRoot? =
                w.feet[entity]?.let { EntityRoot(it, if (entity == caster) w.casterYaw else 0.0, 0.0, 1.62, 1.8) }

            override fun raycastBlock(from: Vec3, to: Vec3): BlockHit? =
                w.solidFromZ?.takeIf { to.z >= it }?.let { BlockHit(Vec3(to.x, to.y, it), Vec3(0.0, 0.0, -1.0), "stone") }
        }
        val movement = object : ISkillMovementGateway {
            override fun isAlive(entity: UUID) = entity in w.alive
            override fun setVelocity(entity: UUID, velocity: Vec3) { w.velocities[entity] = velocity }
            override fun addVelocity(entity: UUID, delta: Vec3) { w.added += entity to delta }
            override fun teleport(entity: UUID, to: Vec3, yawDeg: Double?): Boolean {
                w.feet[entity] = to
                if (entity == caster) w.teleports += to
                return true
            }
            override fun grantInvulnerable(entity: UUID, safetyTicks: Int): Boolean { w.immune = true; return true }
            override fun releaseInvulnerable(entity: UUID) { w.immune = false }
        }
        val gameplay = object : ISkillGameplayGateway {
            override fun removeStatus(victim: UUID, statusId: String) = Unit
            override fun cleanse(victim: UUID, negativeOnly: Boolean) = Unit
            override fun ignite(caster: UUID, victim: UUID, durationTicks: Int) = Unit
            override fun silence(caster: UUID, victim: UUID, durationTicks: Int) = Unit
            override fun modifyAttribute(caster: UUID, victim: UUID, attribute: String, operation: String, amount: Double, durationTicks: Int?) = Unit
            override fun restoreResource(victim: UUID, resource: String, amount: Double) = Unit
            override fun damage(caster: UUID, victim: UUID, components: List<SkillDamage>, hitPoint: Vec3) = Unit
            override fun applyStatus(caster: UUID, victim: UUID, statusId: String, stacks: Int, durationTicks: Int) = Unit
            override fun impulse(victim: UUID, vector: Vec3) { w.impulses += victim to vector }
            override fun freezeEntities(entities: List<UUID>, ticks: Int) = Unit
        }
        val visuals = object : ISkillVisualGateway {
            override fun spawnMesh(castOwner: UUID, item: String, part: String?, pose: VisualPose, brightness: Int?) = VisualHandle(0)
            override fun spawnPlate(castOwner: UUID, sprite: String, alpha: Double, pose: VisualPose, brightness: Int?) = VisualHandle(0)
            override fun spawnBlock(castOwner: UUID, block: String, pose: VisualPose, brightness: Int?) = VisualHandle(0)
            override fun spawnQuad(castOwner: UUID, colorHex: String, alpha: Double, text: String?, shadow: Boolean, seeThrough: Boolean, pose: VisualPose, brightness: Int?) = VisualHandle(0)
            override fun spawnSkinBone(castOwner: UUID, bone: com.astarworks.astera.core.domain.animation.skinrig.SkinRigBone, slim: Boolean, ghost: Boolean, pose: VisualPose) = VisualHandle(0)
            override fun spawnLine(castOwner: UUID, colorHex: String, pose: VisualPose) = VisualHandle(0)
            override fun spawnTrail(castOwner: UUID, ticks: Int, widthFrac: Double, colorHex: String, alpha: Double) = VisualHandle(0)
            override fun write(handle: VisualHandle, pose: VisualPose, interpolationTicks: Int) = Unit
            override fun pushTrailPoint(handle: VisualHandle, root: Vec3, tip: Vec3) = Unit
            override fun remove(handle: VisualHandle) = Unit
        }
        val ports = SkillRuntimePorts(
            visuals = visuals,
            particles = object : ISkillParticleGateway { override fun emit(burst: ParticleBurst) = Unit },
            sounds = object : ISkillSoundGateway { override fun play(sound: String, at: Vec3, volume: Double, pitch: Double) = Unit },
            gameplay = gameplay,
            entities = entities,
            victimAnim = object : ISkillVictimAnimGateway { override fun play(victim: UUID, anim: String, strength: Double, awayFrom: Vec3) = Unit },
        )
        val host = object : MovementHost {
            override fun caster(ctx: OpContext) = caster
            override fun targets(ctx: OpContext, target: TargetRef): List<UUID> = when (target) {
                TargetRef.Caster -> listOf(caster)
                TargetRef.Victim -> listOfNotNull(w.victim)
                else -> emptyList()
            }
            override fun location(ctx: OpContext, spec: LocationSpec): Vec3? = (spec as? LocationSpec.AtValue)?.let { ctx.values.vec3(it.value) }
            override fun runWithVictim(ctx: OpContext, victim: UUID, point: Vec3, ops: List<SkillOp>) {
                w.hits += victim
                w.immuneTrace += w.immune
            }
        }
        return Env(w, ports, movement, host)
    }

    private val literals = object : ValueResolver {
        @Suppress("UNCHECKED_CAST")
        private fun <T> lit(v: Value<T>): T = (v as Value.Literal<T>).value
        override fun num(value: Value<Double>) = lit(value)
        override fun vec3(value: Value<Vec3>) = lit(value)
        override fun quat(value: Value<Quaternion>) = lit(value)
        override fun bool(value: Value<Boolean>) = lit(value)
        override fun text(value: Value<String>) = lit(value)
    }

    private fun ctxOf(e: Env) = object : OpContext {
        override val host: com.astarworks.astera.core.application.skillengine.runtime.OpHost get() = error("this scenario never reaches the cast host")
        override val cast = CastId(1)
        override val tree: FrameTree get() = error("movement ops never touch the frame tree")
        override val values = literals
        override val ports = e.ports
        override fun raise(event: String) = Unit
        override fun run(ops: List<SkillOp>) = Unit
    }

    @Suppress("UNCHECKED_CAST")
    private fun run(e: Env, driver: DashStrikeDriver, op: SkillOp) {
        val ex = movementExecutors(e.host, e.movement, driver, com.astarworks.astera.core.application.skillengine.runtime.ops.MovementLockDriver(e.movement)).first { it.opClass.isInstance(op) } as OpExecutor<SkillOp>
        ex.execute(op, ctxOf(e))
    }

    private fun dash(
        distance: Double = 8.0, ticks: Double = 4.0, gap: Double? = null, local: Vec3? = null,
        hitOnce: Boolean = true, stopOnBlock: Boolean = true,
    ) = MovementOp.DashStrike(
        "d", null, literal(distance), literal(ticks), literal(1.0), hitOnce, true, stopOnBlock, false, gap, local, false,
        listOf(MovementOp.Knockup("k", null, TargetRef.Victim, literal(0.5))),
    )

    private fun setup(): Triple<Env, DashStrikeDriver, World> {
        val e = env(caster)
        e.world.feet[caster] = Vec3(0.0, 0.0, 0.0)
        e.world.alive += listOf(caster, enemyA, enemyB)
        return Triple(e, DashStrikeDriver(e.ports, e.movement, e.host), e.world)
    }

    @Test
    fun `dash stops before the target hits it once and is invulnerable only while dashing`() {
        val (e, driver, w) = setup()
        w.feet[enemyA] = Vec3(0.0, 0.0, 6.0)
        run(e, driver, dash(gap = 1.0))
        assertThat(w.immune).isTrue()
        repeat(6) { driver.tick() }
        // body centres share height, so the corridor travel is 6.0 - 1.0 gap
        assertThat(w.feet.getValue(caster).z).isCloseTo(5.0, offset(1e-9))
        assertThat(w.hits).containsExactly(enemyA)
        assertThat(w.immuneTrace).containsOnly(true)
        assertThat(w.immune).isFalse()
        assertThat(w.velocities[caster]).isEqualTo(Vec3.ZERO)
        assertThat(driver.isDashing(CastId(1))).isFalse()
    }

    @Test
    fun `hitOnce false strikes on every overlapping step`() {
        val (e, driver, w) = setup()
        w.feet[enemyA] = Vec3(0.0, 0.0, 4.0)
        run(e, driver, dash(hitOnce = false))
        repeat(6) { driver.tick() }
        assertThat(w.hits.count { it == enemyA }).isGreaterThan(1)
    }

    @Test
    fun `local direction is relative to the caster heading`() {
        val (e, driver, w) = setup()
        run(e, driver, dash(distance = 4.0, ticks = 2.0, local = Vec3(1.0, 0.0, 0.0)))
        driver.tick()
        val left = Frame.fromDirection(Vec3.ZERO, Vec3(0.0, 0.0, 1.0)).left()
        val end = w.feet.getValue(caster)
        assertThat(end.x).isCloseTo(left.x * 4.0, offset(1e-9))
        assertThat(end.z).isCloseTo(left.z * 4.0, offset(1e-9))

        // Turn the caster 90 degrees: the same local direction follows the heading.
        val (e2, d2, w2) = setup()
        w2.casterYaw = 90.0
        run(e2, d2, dash(distance = 4.0, ticks = 2.0, local = Vec3(1.0, 0.0, 0.0)))
        d2.tick()
        assertThat(w2.feet.getValue(caster).x).isCloseTo(0.0, offset(1e-6)) // heading is now -X, left is +/-Z
        assertThat(kotlin.math.abs(w2.feet.getValue(caster).z)).isCloseTo(4.0, offset(1e-6))
    }

    @Test
    fun `solid block stops the dash and releases immunity`() {
        val (e, driver, w) = setup()
        w.solidFromZ = 2.5
        run(e, driver, dash(distance = 8.0, ticks = 8.0))
        repeat(8) { driver.tick() }
        assertThat(w.feet.getValue(caster).z).isLessThan(2.5)
        assertThat(driver.isDashing(CastId(1))).isFalse()
        assertThat(w.immune).isFalse()
    }

    @Test
    fun `cancel ends a running dash`() {
        val (e, driver, w) = setup()
        run(e, driver, dash())
        driver.cancel(CastId(1))
        assertThat(driver.isDashing(CastId(1))).isFalse()
        assertThat(w.immune).isFalse()
    }

    @Test
    fun `impulse knockback knockup propel setVelocity and leap reach the right gateways`() {
        val (e, driver, w) = setup()
        w.victim = enemyA
        w.feet[enemyA] = Vec3(0.0, 0.0, 3.0)
        run(e, driver, MovementOp.Knockback("a", null, TargetRef.Victim, literal(Vec3(0.0, 0.0, 1.0)), literal(2.0)))
        run(e, driver, MovementOp.Knockup("b", null, TargetRef.Victim, literal(0.6)))
        run(e, driver, MovementOp.Impulse("c", null, TargetRef.Victim, literal(Vec3(1.0, 0.0, 0.0))))
        assertThat(w.impulses.map { it.second }).containsExactly(Vec3(0.0, 0.0, 2.0), Vec3(0.0, 0.6, 0.0), Vec3(1.0, 0.0, 0.0))
        run(e, driver, MovementOp.Propel("p", null, TargetRef.Caster, literal(Vec3(0.0, 1.0, 0.0)), literal(0.5)))
        assertThat(w.added).containsExactly(caster to Vec3(0.0, 0.5, 0.0))
        run(e, driver, MovementOp.SetVelocity("s", null, TargetRef.Victim, literal(Vec3(0.0, 1.0, 0.0)), literal(0.3)))
        assertThat(w.velocities[enemyA]).isEqualTo(Vec3(0.0, 0.3, 0.0))

        val dest = Vec3(8.0, 2.0, 0.0)
        run(e, driver, MovementOp.Leap("l", null, TargetRef.Caster, LocationSpec.AtValue(literal(dest)), literal(2.0)))
        val v = w.velocities.getValue(caster)
        val g = LeapArc.GRAVITY
        val flight = (v.y + sqrt(v.y * v.y - 2 * g * dest.y)) / g
        assertThat(v.x * flight).isCloseTo(dest.x, offset(1e-9))
        assertThat(v.y * flight - g * flight * flight / 2.0).isCloseTo(dest.y, offset(1e-9))

        run(e, driver, MovementOp.Teleport("t", null, TargetRef.Caster, LocationSpec.AtValue(literal(Vec3(1.0, 2.0, 3.0)))))
        assertThat(w.teleports.last()).isEqualTo(Vec3(1.0, 2.0, 3.0))
    }

    @Test
    fun `dead victims are skipped`() {
        val (e, driver, w) = setup()
        w.victim = enemyB
        w.alive -= enemyB
        run(e, driver, MovementOp.Knockup("b", null, TargetRef.Victim, literal(1.0)))
        assertThat(w.impulses).isEmpty()
    }

    private class MapReader(val map: Map<String, Any>) : OpReader {
        override fun textValue(key: String): Value<String>? = null
        override fun entries(key: String): List<OpReader> = emptyList()
        override val path = "test"
        private fun numOf(v: Any) = literal((v as Number).toDouble())
        override fun string(key: String) = map[key] as? String
        override fun requireString(key: String) = string(key) ?: fail("missing $key")
        override fun int(key: String) = (map[key] as? Number)?.toInt()
        override fun bool(key: String) = map[key] as? Boolean
        override fun number(key: String): Value<Double>? = map[key]?.let(::numOf)
        override fun requireNumber(key: String) = number(key) ?: fail("missing $key")
        @Suppress("UNCHECKED_CAST")
        override fun vec3(key: String): Value<Vec3>? = (map[key] as? List<Number>)?.let { literal(Vec3(it[0].toDouble(), it[1].toDouble(), it[2].toDouble())) }
        override fun boolValue(key: String): Value<Boolean>? = null
        override fun point(key: String) = (map[key] as? String)?.let(PointRef::parse)
        override fun stringList(key: String): List<String>? = null
        override fun ops(key: String): List<SkillOp> = emptyList()
        override fun raw(key: String) = map[key]
        override fun fail(message: String): Nothing = throw IllegalArgumentException(message)
    }

    @Test
    fun `decoder maps dash_strike yaml with its defaults and rejects bad direction`() {
        val decoder = movementDecoders().single()
        assertThat(decoder.section).isEqualTo(OpSection.GAMEPLAY)
        assertThat(decoder.opNames).contains("dash_strike", "leap", "propel", "knockback", "impulse", "knockup", "set_velocity", "teleport")
        val op = decoder.decode(
            "dash_strike", "d", null,
            MapReader(mapOf("distance" to 6, "ticks" to 3, "stop_before_target" to 1.5, "local_direction" to listOf(1, 0, 0), "face_movement" to true)),
        ) as MovementOp.DashStrike
        assertThat(op.hitOnce).isTrue()
        assertThat(op.invulnerable).isTrue()
        assertThat(op.stopOnBlock).isTrue()
        assertThat(op.followPitch).isFalse()
        assertThat(op.stopBeforeTarget).isEqualTo(1.5)
        assertThat(op.localDirection).isEqualTo(Vec3(1.0, 0.0, 0.0))
        assertThat(op.faceMovement).isTrue()
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            decoder.decode("dash_strike", "d", null, MapReader(mapOf("distance" to 6, "ticks" to 3, "local_direction" to listOf(0, 0, 0))))
        }
        val leap = decoder.decode("leap", "l", null, MapReader(mapOf("to" to "enemy.feet", "arc_height" to 3))) as MovementOp.Leap
        assertThat(leap.destination).isEqualTo(LocationSpec.AtPoint(PointRef("enemy", "feet")))
        assertThat(leap.target).isEqualTo(TargetRef.Caster)
    }
}
