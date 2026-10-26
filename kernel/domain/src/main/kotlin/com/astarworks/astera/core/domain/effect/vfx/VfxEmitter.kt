package com.astarworks.astera.core.domain.effect.vfx

import com.astarworks.astera.core.domain.effect.DisplayTransform
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.geometry.math.SeededRandom
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
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

/** First flipbook frame of an emitted sprite particle. */
public sealed interface VfxStartFrame {
    /** Drawn per particle, uniformly over the sprite's frames. */
    public data object Random : VfxStartFrame
    public data class Fixed(public val frame: Int) : VfxStartFrame
}

/**
 * Display-particle emitter (element field `emit`): the planner precomputes every particle's
 * trajectory (`v.y -= gravity`, `v *= 1 - drag`, `p += v` per tick, in WORLD axes so gravity
 * always pulls down) with a per-cast seeded RNG. Sizes are quad edge lengths in blocks (for sprite
 * elements: the display scale factor). The `*OverLife` curves are sampled over the particle's lifetime.
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
    /** Size multiplier over the lifetime. */
    public val sizeOverLife: VfxCurve<Double> = VfxCurve.constant(1.0),
    /** RGB over the lifetime (quads only); `null` = the kind's colour. */
    public val colorOverLife: VfxCurve<Int>? = null,
    /** Alpha over the lifetime; `null` = the kind's alpha. */
    public val alphaOverLife: VfxCurve<Double>? = null,
    /** Roll about the view axis, degrees per tick over the lifetime (random sign per particle). */
    public val spinDegPerTick: VfxCurve<Double> = VfxCurve.constant(0.0),
    /** Sprite elements: first flipbook frame. */
    public val startFrame: VfxStartFrame = VfxStartFrame.Random,
    /** Sprite elements: per-particle ticks per flipbook frame; `null` = the sprite kind's `frameTicks`. */
    public val frameTicksMin: Int? = null,
    public val frameTicksMax: Int? = null,
) {
    init {
        require(count in 1..MAX_COUNT) { "emit.count must be 1..$MAX_COUNT: $count" }
        require(ratePerTick == null || ratePerTick >= 1) { "emit.rate_per_tick must be >= 1: $ratePerTick" }
        require(speedMin >= 0.0 && speedMax >= speedMin) { "emit.speed must be [min, max] with 0 <= min <= max" }
        require(spreadDeg in 0.0..180.0) { "emit.spread_deg must be 0..180: $spreadDeg" }
        require(drag in 0.0..1.0) { "emit.drag must be 0..1: $drag" }
        require(lifeMin >= 1 && lifeMax >= lifeMin) { "emit.life_ticks must be [min, max] with 1 <= min <= max" }
        require(sizeMin > 0.0 && sizeMax >= sizeMin) { "emit.size must be [min, max] with 0 < min <= max" }
        require(sizeOverLife.keys.all { it.v >= 0.0 }) { "emit.size_over_life values must be >= 0" }
        require(colorOverLife == null || colorOverLife.keys.all { it.v in 0..0xFFFFFF }) { "emit.color_over_life must be 0xRRGGBB" }
        require(alphaOverLife == null || alphaOverLife.keys.all { it.v in 0.0..1.0 }) { "emit.alpha_over_life must be 0..1" }
        require((frameTicksMin == null) == (frameTicksMax == null)) { "emit.frame_ticks must be [min, max]" }
        require(frameTicksMin == null || (frameTicksMin >= 1 && frameTicksMax!! >= frameTicksMin)) { "emit.frame_ticks must be [min, max] with 1 <= min <= max" }
    }

    public companion object {
        /** Per-element particle cap (each particle is one display entity of the per-cast budget). */
        public const val MAX_COUNT: Int = 40
    }
}

/** Plans [VfxEmitter] elements: one single-quad (or sprite) [VfxElementPlan] per particle. Pure and deterministic per seed. */
internal object VfxEmitterPlanner {

    private const val MAX_STEP_SPIN_DEG = 170.0

    /**
     * `true`: colour / alpha are sent once per curve key and interpolate on the client together with the
     * transformation (position and flipbook swaps ride on separate `keepTransform` updates, so they never restart
     * that window). `false`: the pre-curve behaviour -- one update per `sample_ticks` chunk, each carrying the
     * transform, the mid-chunk style (snapped) and the position. Flip this if a live capture shows style does
     * not share the transform's interpolation window.
     */
    internal const val STYLE_PER_KEY: Boolean = true

    fun plan(
        def: VfxDefinition,
        el: VfxElement,
        elementIndex: Int,
        anchorRot: Quaternion,
        seed: Long,
        scale: Double,
        sprites: SpriteFrames = SpriteFrames.NONE,
    ): List<VfxElementPlan> {
        val emit = checkNotNull(el.emit)
        val quad = el.kind as? VfxElementKind.Quad
        val sprite = el.kind as? VfxElementKind.Sprite
        val frameCount = sprite?.let { (sprites.frames(it.spriteId) ?: 1).coerceIn(1, SpriteGlyphs.MAX_FRAMES) } ?: 1
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
            // Sprite flipbook draws come last so quad seeds stay what they were.
            val startFrame = if (sprite == null) 0 else when (val sf = emit.startFrame) {
                VfxStartFrame.Random -> (rng.nextDouble() * frameCount).toInt().coerceAtMost(frameCount - 1)
                is VfxStartFrame.Fixed -> sf.frame % frameCount
            }
            val frameTicks = if (sprite == null) 1 else if (emit.frameTicksMin != null) {
                val fspan = emit.frameTicksMax!! - emit.frameTicksMin
                emit.frameTicksMin + (rng.nextDouble() * (fspan + 1)).toInt().coerceAtMost(fspan)
            } else {
                sprite.frameTicks
            }
            val despawn = min(def.durationTicks, spawn + lifetime)
            val life = despawn - spawn
            if (life <= 0) continue
            val animLife = life - VfxTimeline.ANIMATION_DELAY_TICKS

            val path = trajectory(origin, dir * speed, emit.gravity * scale, emit.drag, max(animLife, 0))
            val spinAngles = cumulativeSpin(emit.spinDegPerTick, animLife)

            fun styleAt(f: Double): VfxQuadStyle? = when {
                quad != null -> {
                    val c = emit.colorOverLife?.rgbAt(f) ?: quad.colorRgb
                    val a = emit.alphaOverLife?.at(f) ?: quad.alpha
                    VfxQuad.style(quad, c, a)
                }
                else -> emit.alphaOverLife?.let { VfxQuadStyle(0, opacity(it.at(f)), null) }
            }
            val animated = emit.colorOverLife != null || emit.alphaOverLife != null

            fun transformAt(u: Int): DisplayTransform {
                val f = if (animLife > 0) (u.toDouble() / animLife).coerceIn(0.0, 1.0) else 0.0
                val size = baseSize * emit.sizeOverLife.at(f)
                val rotation = Quaternion.fromEulerDegrees(0.0, 0.0, spinSign * spinAngles[u.coerceIn(0, spinAngles.size - 1)])
                val sc = Vec3(size, size, 1.0)
                return if (quad != null) VfxQuad.transform(quad, rotation, sc) else DisplayTransform(Vec3.ZERO, rotation, sc)
            }

            // ---- transform / style steps -------------------------------------------------
            val steps = if (animLife > 0) transformSteps(emit, def.sampleTicks, animLife, ::styleAt, animated) else emptyList()
            val stepAt = steps.associateBy { it.start }

            // ---- position steps (separate teleport windows) ------------------------------
            val posStarts = sortedSetOf<Int>()
            if (animLife > 0) {
                if (STYLE_PER_KEY) {
                    var t = 0
                    while (t < animLife) {
                        posStarts += t
                        t += def.sampleTicks
                    }
                }
                steps.forEach { posStarts += it.start }
            }
            val posList = posStarts.toList()
            val posEnd = HashMap<Int, Int>()
            posList.forEachIndexed { i, t -> posEnd[t] = if (i + 1 < posList.size) posList[i + 1] else animLife }

            // ---- flipbook swaps ----------------------------------------------------------
            val swaps = HashMap<Int, String>()
            if (sprite != null && frameCount >= 2) {
                var k = 1
                while (k * frameTicks < animLife) {
                    val index = startFrame + k
                    if (!sprite.loop && index >= frameCount) break
                    swaps[k * frameTicks] = SpriteGlyphs.text(sprite.spriteId, index % frameCount, sprite.colorArgb)
                    k++
                }
            }

            val ticks = sortedSetOf<Int>().apply {
                addAll(stepAt.keys)
                addAll(posStarts)
                addAll(swaps.keys)
            }
            val updates = ArrayList<VfxUpdate>(ticks.size)
            var current = transformAt(0)
            for (t in ticks) {
                val step = stepAt[t]
                if (step != null) current = transformAt(step.end)
                val pEnd = posEnd[t]
                updates += VfxUpdate(
                    tick = spawn + VfxTimeline.ANIMATION_DELAY_TICKS + t,
                    transform = current,
                    interpolationTicks = step?.let { it.end - it.start } ?: 0,
                    text = swaps[t],
                    style = step?.style,
                    position = pEnd?.let { path[it] },
                    positionTicks = pEnd?.let { it - t },
                    keepTransform = step == null,
                )
            }

            val initialStyle = styleAt(0.0)
            val kind: VfxElementKind = if (quad != null) {
                quad.copy(
                    colorRgb = emit.colorOverLife?.rgbAt(0.0) ?: quad.colorRgb,
                    alpha = emit.alphaOverLife?.at(0.0) ?: quad.alpha,
                )
            } else {
                VfxElementKind.Text(
                    text = SpriteGlyphs.text(sprite!!.spriteId, startFrame, sprite.colorArgb),
                    backgroundArgb = 0,
                    shadow = false,
                    seeThrough = sprite.seeThrough,
                    textOpacity = initialStyle?.textOpacity ?: 255,
                )
            }
            out += VfxElementPlan(
                elementId = el.id,
                slot = slot,
                kind = kind,
                brightness = el.brightness,
                glowArgb = el.glowArgb,
                billboard = el.billboard,
                viewRange = el.viewRange,
                spawnTick = spawn,
                despawnTick = despawn,
                initial = transformAt(0),
                updates = updates,
                particles = emptyList(),
                spawnOffset = path[0],
            )
        }
        return out
    }

    /** One client-interpolated transform step `[start, end]` (element ticks) and the style it ends at, if it changes. */
    private class Step(val start: Int, val end: Int, val style: VfxQuadStyle?)

    private fun opacity(alpha: Double): Int =
        (alpha.coerceIn(0.0, 1.0) * 255).roundToInt().coerceIn(VfxQuad.MIN_TEXT_OPACITY, 255)

    /** Accumulated spin (degrees) at integer element ticks `0..animLife`. */
    private fun cumulativeSpin(curve: VfxCurve<Double>, animLife: Int): DoubleArray {
        val out = DoubleArray(max(animLife, 0) + 1)
        for (i in 0 until animLife) out[i + 1] = out[i] + curve.at((i + 0.5) / animLife)
        return out
    }

    private fun transformSteps(
        emit: VfxEmitter,
        sampleTicks: Int,
        animLife: Int,
        styleAt: (Double) -> VfxQuadStyle?,
        animated: Boolean,
    ): List<Step> {
        val maxSpin = emit.spinDegPerTick.keys.maxOf { abs(it.v) }
        val spinChunk = if (maxSpin > 0.0) max(1, (MAX_STEP_SPIN_DEG / maxSpin).toInt()) else Int.MAX_VALUE
        if (!STYLE_PER_KEY) {
            val chunk = min(sampleTicks, spinChunk)
            val out = ArrayList<Step>()
            var start = 0
            while (start < animLife) {
                val end = min(start + chunk, animLife)
                out += Step(start, end, if (animated) styleAt(((start + end) / 2.0) / animLife) else null)
                start = end
            }
            return out
        }
        // Breakpoints: every curve key, so each key segment is one linear client interpolation.
        val breaks = sortedSetOf(0, animLife)
        fun addKeys(ts: List<Double>) = ts.forEach { breaks += (it * animLife).roundToInt().coerceIn(0, animLife) }
        addKeys(emit.sizeOverLife.keys.map { it.t })
        addKeys(emit.spinDegPerTick.keys.map { it.t })
        emit.alphaOverLife?.let { c -> addKeys(c.keys.map { it.t }) }
        emit.colorOverLife?.let { c -> addKeys(c.keys.map { it.t }) }
        val list = breaks.toList()
        val out = ArrayList<Step>()
        for (i in 1 until list.size) {
            val a = list[i - 1]
            val b = list[i]
            // Eased / spinning segments need intermediate samples; plain linear ones go out as ONE update.
            val chunk = min(spinChunk, if (deviatesFromLinear(emit, a, b, animLife)) sampleTicks else Int.MAX_VALUE)
            val n = if (chunk == Int.MAX_VALUE) 1 else (b - a + chunk - 1) / chunk
            var prev = a
            for (j in 1..n) {
                val cut = if (j == n) b else a + (j.toLong() * (b - a) / n).toInt()
                if (cut > prev) {
                    val s0 = if (animated) styleAt(prev.toDouble() / animLife) else null
                    val s1 = if (animated) styleAt(cut.toDouble() / animLife) else null
                    out += Step(prev, cut, s1.takeIf { it != s0 })
                    prev = cut
                }
            }
        }
        return out
    }

    /** Whether size / alpha / colour bend away from the straight line between the segment's end values. */
    private fun deviatesFromLinear(emit: VfxEmitter, a: Int, b: Int, animLife: Int): Boolean {
        if (b - a < 2) return false
        val fa = a.toDouble() / animLife
        val fb = b.toDouble() / animLife
        for (q in doubleArrayOf(0.25, 0.5, 0.75)) {
            val f = fa + (fb - fa) * q
            if (bends(emit.sizeOverLife.at(fa), emit.sizeOverLife.at(fb), emit.sizeOverLife.at(f), q, 0.01)) return true
            emit.alphaOverLife?.let { if (bends(it.at(fa), it.at(fb), it.at(f), q, 0.01)) return true }
            emit.colorOverLife?.let { c ->
                val ca = c.rgbAt(fa)
                val cb = c.rgbAt(fb)
                val mid = c.rgbAt(f)
                for (shift in intArrayOf(16, 8, 0)) {
                    if (bends(((ca shr shift) and 0xFF) / 255.0, ((cb shr shift) and 0xFF) / 255.0, ((mid shr shift) and 0xFF) / 255.0, q, 0.02)) return true
                }
            }
        }
        return false
    }

    private fun bends(v0: Double, v1: Double, actual: Double, q: Double, tolerance: Double): Boolean =
        abs(actual - (v0 + (v1 - v0) * q)) > tolerance

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
}
