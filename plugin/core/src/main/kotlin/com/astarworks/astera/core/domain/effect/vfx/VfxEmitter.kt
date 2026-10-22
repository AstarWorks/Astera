package com.astarworks.astera.core.domain.effect.vfx

import com.astarworks.astera.core.domain.effect.DisplayTransform
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.geometry.math.SeededRandom
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Base direction of an emitted particle, in the anchor's local axes (x = left, y = up, z = forward). */
public sealed interface VfxEmitDirection {
    public data object Forward : VfxEmitDirection
    public data object Up : VfxEmitDirection

    /** Uniformly random over the sphere (spread is ignored). */
    public data object Outward : VfxEmitDirection

    /** Sideways: local +x (left). */
    public data object Tangent : VfxEmitDirection
    public data class Custom(public val vector: Vec3) : VfxEmitDirection {
        init {
            require(vector.lengthSquared() > 1e-9) { "VfxEmitDirection.Custom must not be the zero vector" }
        }
    }
}

/**
 * Display-particle emitter (element field `emit`): the planner precomputes every particle's
 * trajectory (`v.y -= gravity`, `v *= 1 - drag`, `p += v` per tick, in WORLD axes so gravity
 * always pulls down) with a per-cast seeded RNG. Sizes are quad edge lengths in blocks.
 */
public data class VfxEmitter(
    public val count: Int,
    /** Particles spawned per tick; `null` = all at the element's start tick (burst). */
    public val ratePerTick: Int? = null,
    public val speedMin: Double = 0.0,
    public val speedMax: Double = 0.0,
    /** Cone half-angle around the base direction, degrees. */
    public val spreadDeg: Double = 0.0,
    public val direction: VfxEmitDirection = VfxEmitDirection.Up,
    /** Blocks / tick^2 downward (negative floats up). */
    public val gravity: Double = 0.0,
    /** Fraction of velocity lost per tick, 0..1. */
    public val drag: Double = 0.0,
    public val lifeMin: Int = 20,
    public val lifeMax: Int = 20,
    public val sizeMin: Double = 0.1,
    public val sizeMax: Double = 0.1,
    /** Size multiplier at birth / death (linear). */
    public val sizeStart: Double = 1.0,
    public val sizeEnd: Double = 1.0,
    /** Evenly spaced RGB stops over the lifetime; empty = the kind's colour. */
    public val colorOverLife: List<Int> = emptyList(),
    /** Evenly spaced alpha stops over the lifetime; empty = the kind's alpha. */
    public val alphaOverLife: List<Double> = emptyList(),
    /** Roll about the view axis, degrees per tick (random sign per particle). */
    public val spinDegPerTick: Double = 0.0,
) {
    init {
        require(count in 1..MAX_COUNT) { "emit.count must be 1..$MAX_COUNT: $count" }
        require(ratePerTick == null || ratePerTick >= 1) { "emit.rate_per_tick must be >= 1: $ratePerTick" }
        require(speedMin >= 0.0 && speedMax >= speedMin) { "emit.speed must be [min, max] with 0 <= min <= max" }
        require(spreadDeg in 0.0..180.0) { "emit.spread_deg must be 0..180: $spreadDeg" }
        require(drag in 0.0..1.0) { "emit.drag must be 0..1: $drag" }
        require(lifeMin >= 1 && lifeMax >= lifeMin) { "emit.life_ticks must be [min, max] with 1 <= min <= max" }
        require(sizeMin > 0.0 && sizeMax >= sizeMin) { "emit.size must be [min, max] with 0 < min <= max" }
        require(sizeStart >= 0.0 && sizeEnd >= 0.0) { "emit.size_over_life must be >= 0" }
        require(colorOverLife.all { it in 0..0xFFFFFF }) { "emit.color_over_life must be 0xRRGGBB" }
        require(alphaOverLife.all { it in 0.0..1.0 }) { "emit.alpha_over_life must be 0..1" }
    }

    public companion object {
        /** Per-element particle cap (each particle is one display entity of the per-cast budget). */
        public const val MAX_COUNT: Int = 40
    }
}

/** Plans [VfxEmitter] elements: one single-quad [VfxElementPlan] per particle. Pure and deterministic per seed. */
internal object VfxEmitterPlanner {

    private const val MAX_STEP_SPIN_DEG = 170.0

    fun plan(def: VfxDefinition, el: VfxElement, elementIndex: Int, anchorRot: Quaternion, seed: Long, scale: Double): List<VfxElementPlan> {
        val emit = checkNotNull(el.emit)
        val kind = el.kind as VfxElementKind.Quad
        val origin = anchorRot.rotate((el.keyframes.firstOrNull()?.translate ?: Vec3.ZERO) * scale)
        val out = ArrayList<VfxElementPlan>(emit.count)
        for (slot in 0 until emit.count) {
            val spawn = el.startTick + (emit.ratePerTick?.let { slot / it } ?: 0)
            if (spawn >= def.durationTicks) continue
            val rng = particleRandom(seed, elementIndex, slot)
            val dir = anchorRot.rotate(direction(emit, rng))
            val speed = rng.nextDouble(emit.speedMin, emit.speedMax) * scale
            val span = emit.lifeMax - emit.lifeMin
            val lifetime = emit.lifeMin + (rng.nextDouble() * (span + 1)).toInt().coerceAtMost(span)
            val baseSize = rng.nextDouble(emit.sizeMin, emit.sizeMax) * scale
            val spinSign = if (rng.nextDouble() < 0.5) -1.0 else 1.0
            val despawn = min(def.durationTicks, spawn + lifetime)
            val life = despawn - spawn
            if (life <= 0) continue
            val animLife = life - VfxTimeline.ANIMATION_DELAY_TICKS

            val path = trajectory(origin, dir * speed, emit.gravity * scale, emit.drag, max(animLife, 0))

            fun styleAt(f: Double): Pair<Int, Double> =
                stops(emit.colorOverLife, f, VfxQuad::lerpRgb, kind.colorRgb) to
                    stops(emit.alphaOverLife, f, { a, b, t -> a + (b - a) * t }, kind.alpha)

            fun transformAt(u: Double): DisplayTransform {
                val f = if (animLife > 0) (u / animLife).coerceIn(0.0, 1.0) else 0.0
                val size = baseSize * (emit.sizeStart + (emit.sizeEnd - emit.sizeStart) * f)
                val rotation = Quaternion.fromEulerDegrees(0.0, 0.0, spinSign * emit.spinDegPerTick * u)
                return VfxQuad.transform(kind, rotation, Vec3(size, size, 1.0))
            }

            val (c0, a0) = styleAt(0.0)
            val chunkMax = if (emit.spinDegPerTick > 0.0) max(1, (MAX_STEP_SPIN_DEG / emit.spinDegPerTick).toInt()) else Int.MAX_VALUE
            val chunk = min(def.sampleTicks, chunkMax)
            val animated = emit.colorOverLife.isNotEmpty() || emit.alphaOverLife.isNotEmpty()
            val updates = ArrayList<VfxUpdate>()
            var start = 0
            while (start < animLife) {
                val end = min(start + chunk, animLife)
                val (c, a) = styleAt(((start + end) / 2.0) / animLife)
                updates += VfxUpdate(
                    tick = spawn + VfxTimeline.ANIMATION_DELAY_TICKS + start,
                    transform = transformAt(end.toDouble()),
                    interpolationTicks = end - start,
                    style = if (animated) VfxQuad.style(kind, c, a) else null,
                    position = path[end],
                )
                start = end
            }
            out += VfxElementPlan(
                elementId = el.id,
                slot = slot,
                kind = kind.copy(colorRgb = c0, alpha = a0),
                brightness = el.brightness,
                glowArgb = el.glowArgb,
                billboard = el.billboard,
                viewRange = el.viewRange,
                spawnTick = spawn,
                despawnTick = despawn,
                initial = transformAt(0.0),
                updates = updates,
                particles = emptyList(),
                spawnOffset = path[0],
            )
        }
        return out
    }

    private fun particleRandom(seed: Long, elementIndex: Int, slot: Int): SeededRandom {
        val mixed = seed xor (elementIndex.toLong() * 0x2545F4914F6CDD1DL) xor (slot.toLong() * 0x9E3779B97F4A7C15uL.toLong()) xor 0x5DEECE66DL
        return SeededRandom(SeededRandom(mixed).nextLong())
    }

    /** Positions (offsets from the anchor) at element ticks `0..ticks`; `v.y -= g; v *= 1 - drag; p += v` each tick. */
    fun trajectory(origin: Vec3, velocity: Vec3, gravity: Double, drag: Double, ticks: Int): List<Vec3> {
        val out = ArrayList<Vec3>(ticks + 1)
        var p = origin
        var v = velocity
        out += p
        repeat(ticks) {
            v = Vec3(v.x, v.y - gravity, v.z) * (1.0 - drag)
            p += v
            out += p
        }
        return out
    }

    private fun direction(emit: VfxEmitter, rng: SeededRandom): Vec3 {
        val base = when (val d = emit.direction) {
            VfxEmitDirection.Forward -> Vec3(0.0, 0.0, 1.0)
            VfxEmitDirection.Up -> Vec3(0.0, 1.0, 0.0)
            VfxEmitDirection.Tangent -> Vec3(1.0, 0.0, 0.0)
            VfxEmitDirection.Outward -> return rng.nextUnitVector()
            is VfxEmitDirection.Custom -> d.vector.normalize()
        }
        if (emit.spreadDeg <= 0.0) return base
        // Uniform on the spherical cap of half-angle spread: cos(theta) uniform in [cos(spread), 1].
        val cosMax = cos(Math.toRadians(emit.spreadDeg))
        val cosT = rng.nextDouble(cosMax, 1.0)
        val sinT = sqrt(max(0.0, 1.0 - cosT * cosT))
        val phi = rng.nextDouble(0.0, 2.0 * PI)
        val helper = if (abs(base.y) < 0.99) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)
        val u = base.cross(helper).normalize()
        val w = base.cross(u)
        return base * cosT + u * (sinT * cos(phi)) + w * (sinT * sin(phi))
    }

    /** Evenly spaced stops sampled at [f] in 0..1; [fallback] when there are none. */
    private fun <T> stops(list: List<T>, f: Double, lerp: (T, T, Double) -> T, fallback: T): T {
        if (list.isEmpty()) return fallback
        if (list.size == 1) return list[0]
        val x = f.coerceIn(0.0, 1.0) * (list.size - 1)
        val i = min(x.toInt(), list.size - 2)
        return lerp(list[i], list[i + 1], x - i)
    }
}
