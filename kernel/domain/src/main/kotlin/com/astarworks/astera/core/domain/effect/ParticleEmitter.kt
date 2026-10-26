package com.astarworks.astera.core.domain.effect

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Curve
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.geometry.math.distribute
import com.astarworks.astera.core.domain.player.PlayerId
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * What the runtime knows about one emission; every field is optional and only consulted when the
 * definition asks for it (velocity direction, trail `toward`, viewer filtering).
 */
public data class ParticleEmitContext(
    /** Caster look direction: orients sampled shapes and resolves `from_caster_look`. */
    val look: Vec3? = null,
    /** Direction of travel of the emitting element (VFX path / formation tangent) for `along_tangent`. */
    val tangent: Vec3? = null,
    /** Centre of the emitting shape / formation (world position) for `outward` on unsampled shapes. */
    val center: Vec3? = null,
    /** World point a `toward` trail / vibration heads for. */
    val toward: Vec3? = null,
    val source: PlayerId? = null,
    val random: Random = Random.Default,
)

/** One particle spawn: the world position plus the final (fully resolved) spec. */
public data class PlannedParticle(val at: Vec3, val spec: ParticleSpec)

/**
 * Pure planner that turns a [ParticleDefinition] into concrete spawns (ADR-0190 follow-up): samples
 * `points` shapes through the core math [Curve] in the caster [Frame], resolves velocity directions
 * into the vanilla directed-particle form (`count = 0`, `offset` = direction, `extra` = speed), and
 * resolves `trail` / `vibration` targets to absolute positions. The runtime only spawns the result.
 */
public object ParticleEmitter {

    internal class Sample(val position: Vec3, val tangent: Vec3, val outward: Vec3)

    /** Points and directions in caster space; null samples retain the vanilla scatter policy. */
    public class BakedShape internal constructor(internal val samples: List<Sample>?, internal val full: Boolean) {
        public val pointCount: Int? get() = samples?.size
    }

    public fun bake(shape: ParticleShape): BakedShape {
        // Store raw curve results: rotating by identity here would change signed-zero/rounding behaviour.
        fun local(c: Curve, points: Int, planar: Boolean = true, spherical: Boolean = false): List<Sample> =
            c.distribute(points).map { t ->
                val position = c.point(t)
                val outward = when {
                    spherical -> position
                    planar -> Vec3(position.x, 0.0, position.z)
                    else -> UP
                }
                Sample(position, c.tangent(t), outward.takeIf { it.lengthSquared() > EPS } ?: UP)
            }
        val samples = when (shape) {
            ParticleShape.Point, is ParticleShape.Burst -> null
            is ParticleShape.Line -> shape.points?.let { local(Curve.Line(Vec3.ZERO, Vec3(0.0, 0.0, shape.lengthBlocks)), it, planar = false) }
            is ParticleShape.Circle -> shape.points?.let { local(Curve.Arc(shape.radiusBlocks, 0.0, 360.0), it) }
            is ParticleShape.Ring -> local(Curve.Arc(shape.radiusBlocks, 0.0, 360.0), shape.points)
            is ParticleShape.Arc -> local(Curve.Arc(shape.radiusBlocks, shape.fromDeg, shape.toDeg), shape.points)
            is ParticleShape.Helix -> local(Curve.Helix(shape.radiusBlocks, shape.turns, shape.heightBlocks), shape.points)
            is ParticleShape.Sphere -> shape.points?.let { local(Curve.FibonacciSphere(shape.radiusBlocks, it), it, spherical = true) }
        }
        return BakedShape(samples, shape is ParticleShape.Line || shape is ParticleShape.Sphere)
    }

    public fun plan(
        def: ParticleDefinition,
        at: Vec3,
        ctx: ParticleEmitContext = ParticleEmitContext(),
        countOverride: Int? = null,
    ): List<PlannedParticle> {
        val base = def.toParticleSpec(countOverride).copy(source = ctx.source)
        val velocity = def.velocity
        val samples = sample(def.bakedShape, at, ctx.look)

        if (samples == null) {
            if (velocity == null) return listOf(PlannedParticle(at, resolveTargets(base, at, ctx)))
            return List(base.count) { PlannedParticle(at, directed(base, velocity, null, at, ctx)) }
        }
        return samples.map { s ->
            val spec = if (velocity == null) {
                base.copy(count = 1, offset = Vec3.ZERO)
            } else {
                directed(base, velocity, s, s.position, ctx)
            }
            PlannedParticle(s.position, resolveTargets(spec, s.position, ctx))
        }
    }

    private fun directed(base: ParticleSpec, v: ParticleVelocity, s: Sample?, at: Vec3, ctx: ParticleEmitContext): ParticleSpec {
        val raw = when (val d = v.direction) {
            VelocityDirection.FromCasterLook -> ctx.look
            VelocityDirection.AlongTangent -> s?.tangent ?: ctx.tangent
            VelocityDirection.Outward ->
                s?.outward ?: ctx.center?.let { at - it }?.takeIf { it.lengthSquared() > EPS } ?: randomUnit(ctx.random)
            VelocityDirection.Up -> UP
            is VelocityDirection.Custom -> d.direction
        }
        val dir = (raw?.takeIf { it.lengthSquared() > EPS } ?: UP).normalize()
        val jittered = if (v.spreadDegrees > 0.0) coneJitter(dir, v.spreadDegrees, ctx.random) else dir
        val speed = if (v.speedJitter > 0.0) v.speed * (1.0 + v.speedJitter * ctx.random.nextDouble(-1.0, 1.0)) else v.speed
        return base.copy(count = 0, offset = jittered, extra = speed)
    }

    private fun resolveTargets(spec: ParticleSpec, at: Vec3, ctx: ParticleEmitContext): ParticleSpec {
        val fallback = at + (ctx.look?.takeIf { it.lengthSquared() > EPS }?.normalize() ?: UP) * FALLBACK_TOWARD
        fun resolve(t: ParticleTarget): ParticleTarget.Absolute = when (t) {
            is ParticleTarget.Absolute -> t
            is ParticleTarget.Offset -> ParticleTarget.Absolute(at + t.offset)
            ParticleTarget.Toward -> ParticleTarget.Absolute(ctx.toward ?: fallback)
        }
        return when (val d = spec.data) {
            is ParticleData.Trail -> spec.copy(data = d.copy(target = resolve(d.target)))
            is ParticleData.Vibration -> spec.copy(data = d.copy(destination = resolve(d.destination)))
            else -> spec
        }
    }

    private fun sample(shape: BakedShape, at: Vec3, look: Vec3?): List<Sample>? {
        val samples = shape.samples ?: return null
        val forward = look?.takeIf { it.lengthSquared() > EPS }
        val yawOnly = forward?.let { Vec3(it.x, 0.0, it.z) }?.takeIf { it.lengthSquared() > EPS } ?: forward
        val direction = if (shape.full) forward else yawOnly
        val frame = Frame(at, direction?.let { Quaternion.lookRotation(it) } ?: Quaternion.IDENTITY)
        return samples.map { local -> Sample(
            position = frame.toWorld(local.position),
            tangent = frame.directionToWorld(local.tangent),
            outward = frame.directionToWorld(local.outward),
        ) }
    }

    private fun randomUnit(r: Random): Vec3 {
        val y = r.nextDouble(-1.0, 1.0)
        val phi = r.nextDouble(0.0, 2.0 * Math.PI)
        val xz = sqrt(1.0 - y * y)
        return Vec3(xz * cos(phi), y, xz * sin(phi))
    }

    /** Uniform direction inside a cone of half-angle [maxDeg] around [dir]. */
    private fun coneJitter(dir: Vec3, maxDeg: Double, r: Random): Vec3 {
        val cosMax = cos(Math.toRadians(maxDeg))
        val cosT = 1.0 - r.nextDouble() * (1.0 - cosMax)
        val sinT = sqrt((1.0 - cosT * cosT).coerceAtLeast(0.0))
        val phi = r.nextDouble(0.0, 2.0 * Math.PI)
        val local = Vec3(sinT * cos(phi), sinT * sin(phi), cosT)
        return Quaternion.lookRotation(dir).rotate(local).normalize()
    }

    private val UP = Vec3(0.0, 1.0, 0.0)
    private const val EPS = 1e-9
    private const val FALLBACK_TOWARD = 3.0
}
