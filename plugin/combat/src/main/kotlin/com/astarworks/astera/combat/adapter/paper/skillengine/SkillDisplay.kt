package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.combat.application.port.outbound.IDisplayEntityGateway.DisplayWrite
import com.astarworks.astera.core.application.skillengine.runtime.port.ParticleBurst
import com.astarworks.astera.core.domain.effect.DisplayBillboard
import com.astarworks.astera.core.domain.effect.DisplayTransform
import com.astarworks.astera.core.domain.effect.ParticleColor
import com.astarworks.astera.core.domain.effect.ParticleData
import com.astarworks.astera.core.domain.effect.ParticleDefinition
import com.astarworks.astera.core.domain.effect.ParticleEmitContext
import com.astarworks.astera.core.domain.effect.ParticleEmitter
import com.astarworks.astera.core.domain.effect.ParticleSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * What one vanilla Display draws at scale 1 (ADR-0303): [unit] is its size in blocks along the display's x / y / z, [centre]
 * the offset (per unit of scale, in the display's own axes) from the entity position to the middle of what is drawn. A
 * skill object states its size in BLOCKS; this table is the only place that knows how a block becomes a scale number.
 */
public data class Intrinsic(val unit: Vec3, val centre: Vec3 = Vec3.ZERO) {
    public companion object {
        /** `item_display` fixed mode: the model is one block per scale unit, centred on the origin. */
        public val ITEM_FIXED: Intrinsic = Intrinsic(Vec3(1.0, 1.0, 1.0))

        /** `block_display`: one block per scale unit; its origin is a corner, so the middle of what is drawn is half a unit toward +x +y +z. */
        public val BLOCK: Intrinsic = Intrinsic(Vec3(1.0, 1.0, 1.0), Vec3(-0.5, -0.5, -0.5))

        /**
         * Trail ribbon scale units per block of length and thickness (the one-line text quad of MC 26.3). Line ribbons no longer use these: they are item displays (ADR-0303 rev4.15).
         * Width: the background of `Component.text(" ")` is not the 5 px a space advances (the v1 derivation of ADR-0220): a real client
         * (vfx-shot, 1080p side view, `rifle-shot` and a 4.0 block / 2.0 block probe line) drew 0.42-0.45 of the asked length at 8 units
         * per block, i.e. the box is [SOLID_BOX_PX_X] = 2.34 px wide (fitted over two probe lines), so one block of length is `1 / (2.34 * 0.025)` scale units.
         * Re-measured after the change: see /tmp/claude-1000/skill-engine/shots-i12/line-length. Height: 10 px = 0.25 block, 4 per block.
         */
        public const val SOLID_BOX_PX_X: Double = 2.34
        public const val SOLID_SCALE_X: Double = 1.0 / (SOLID_BOX_PX_X * 0.025)
        public const val SOLID_SCALE_Y: Double = 4.0
        public const val CENTER_X_PER_SCALE: Double = 0.0125
        public const val CENTER_Y_PER_SCALE: Double = 0.125

        /**
         * `kind: text` (ADR-0303 2.1: the author writes the plain vanilla `scale`, so [unit] is 1): a one-line text box whose middle is
         * off the entity origin by (+.0125, +.125) blocks per scale unit (MC 26.3 TextDisplayRenderer, calibrated against a client capture).
         * [toDisplayWrite] puts `translation = rotation * (-centre * scale)` before the rotation and scale, which is the quad transform
         * for solid quads and glyph quads alike, so the quad's middle lands on the entity position.
         */
        public val TEXT_QUAD: Intrinsic = Intrinsic(Vec3(1.0, 1.0, 1.0), Vec3(-CENTER_X_PER_SCALE, -CENTER_Y_PER_SCALE, 0.0))

        /**
         * `kind: sprite` (ADR-0303 2.1): the loader already turned the authored `size` in blocks into the vanilla `scale` (1 = the natural
         * quad, font height px x 0.025 blocks), so [unit] is 1, and there is no centre: the sprite font's ascent (size/2 - 2, ADR-0239)
         * puts the glyph's middle on the entity origin, which is the zero translation for a sprite and what `pivot: center` means.
         */
        public val SPRITE: Intrinsic = Intrinsic(Vec3(1.0, 1.0, 1.0))
    }
}

/**
 * The single function from a world pose to the vanilla fields (ADR-0303): `location` = the frame origin, `left_rotation` =
 * the frame rotation, `scale` = [size] / [Intrinsic.unit] (z of a text quad is 1), `translation` = the intrinsic centre
 * correction rotated into the frame, `right_rotation` identity, one interpolation length for position, rotation and scale.
 * Plates, meshes, lines and trail pieces all go through it; Paper assigns the result and the sim records it.
 *
 * A viewer-facing billboard owns the rotation, so [world] then carries only the origin and the display is never anchored
 * (a billboard turns the whole local frame: a passenger offset would swing with the camera).
 */
public fun toDisplayWrite(
    world: Frame,
    size: Vec3,
    intrinsic: Intrinsic,
    billboard: DisplayBillboard,
    ticks: Int,
    anchor: DisplayWrite.Anchor? = null,
    teleportTicks: Int? = null,
): DisplayWrite {
    val scale = Vec3(size.x / intrinsic.unit.x, size.y / intrinsic.unit.y, size.z / intrinsic.unit.z)
    val centre = Vec3(intrinsic.centre.x * scale.x, intrinsic.centre.y * scale.y, intrinsic.centre.z * scale.z)
    return DisplayWrite(
        at = world.origin, yaw = 0f, pitch = 0f,
        transform = DisplayTransform(world.rotation.rotate(centre), world.rotation, scale),
        billboard = billboard,
        interpolationDelay = 0, interpolationDuration = ticks,
        teleportDuration = if (anchor != null && billboard == DisplayBillboard.FIXED) 0 else teleportTicks ?: ticks, // a rider never teleports
        anchor = anchor.takeIf { billboard == DisplayBillboard.FIXED },
    )
}

/**
 * Transformation of a display that rides its caster as a native passenger (ADR-0276): the riding origin is the caster's
 * position, so the translation carries the offset of the pose from the feet it was computed against, minus the passenger
 * seat height.
 */
public fun ridingTransform(write: DisplayWrite, seatHeight: Double): DisplayTransform {
    val anchor = checkNotNull(write.anchor) { "not anchored" }
    return write.transform.copy(
        translation = write.transform.translation + (write.at - anchor.feet) - Vec3(0.0, seatHeight, 0.0),
    )
}

/** Columns of a trail segment's transformation matrix: travel [u], root-to-tip [v], [normal]; plus the translation. */
public class RibbonColumns(public val col0: Vec3, public val col1: Vec3, public val col2: Vec3, public val shift: Vec3)

public fun ribbonColumns(u: Vec3, v: Vec3, normal: Vec3): RibbonColumns {
    val col0 = u * Intrinsic.SOLID_SCALE_X
    val col1 = v * Intrinsic.SOLID_SCALE_Y
    return RibbonColumns(col0, col1, normal, col0 * -Intrinsic.CENTER_X_PER_SCALE + col1 * -Intrinsic.CENTER_Y_PER_SCALE)
}

/** One `World.spawnParticle` call: the position and the vanilla fields (`count`, offset, `extra` = speed, dust colour / size). */
public data class ParticleCall(val at: Vec3, val spec: ParticleSpec)

private const val MAX_PER_BURST = 64

/** Custom ids are lower-kebab ([com.astarworks.astera.core.domain.effect.ParticleId]); vanilla keys use underscores. */
private val CUSTOM_ID = Regex("^[a-z][a-z0-9-]*$")

/**
 * A burst as the vanilla particle calls it makes (ADR-0303), field for field: `at` is the burst position, `count` / `offset`
 * / `extra` / colour / size are the op's values untouched (vanilla `count 0` keeps its direction meaning). The only
 * expansion is the explicit `dir` sugar: `count` calls of (`count 0`, `offset = jitter(dir, spread) * speed + inherited
 * velocity`, `extra 1`), deterministic from [ParticleBurst.seed]. A custom definition owns shape and velocity.
 */
public fun toParticleCalls(burst: ParticleBurst, definitions: (String) -> ParticleDefinition? = { null }): List<ParticleCall> {
    val definition = if (CUSTOM_ID.matches(burst.particle)) definitions(burst.particle) else null
    val color = burst.colorHex?.let(::particleColor)
    if (definition != null) {
        val ctx = ParticleEmitContext(look = burst.dir ?: burst.look, random = kotlin.random.Random(burst.seed))
        return ParticleEmitter.plan(definition, burst.at, ctx, countOverride = burst.count)
            .map { ParticleCall(it.at, if (color != null) it.spec.copy(color = color) else it.spec) }
    }
    val scale = burst.dataSize?.toFloat() ?: 1.0f
    val data = burst.blockKey?.let { ParticleData.Block(it) } ?: burst.itemKey?.let { ParticleData.Item(it) }
    val dir = burst.dir
        ?: return listOf(
            ParticleCall(burst.at, ParticleSpec(burst.particle, count = burst.count ?: 1, offset = burst.offset, extra = burst.speed, color = color, scale = scale, data = data)),
        )
    val rng = Random(burst.seed)
    val axis = dir.normalize().takeIf { it.lengthSquared() > 0.0 }
    return List((burst.count ?: 1).coerceIn(0, MAX_PER_BURST)) {
        val direction = if (axis != null) cone(axis, burst.spreadDeg, rng) else Vec3.ZERO
        val velocity = direction * burst.speed + (burst.inheritVelocity ?: Vec3.ZERO)
        ParticleCall(burst.at, ParticleSpec(burst.particle, count = 0, offset = velocity, extra = 1.0, color = color, scale = scale, data = data))
    }
}

/** Uniform direction within [spreadDeg] of the unit [axis]. */
private fun cone(axis: Vec3, spreadDeg: Double, rng: Random): Vec3 {
    if (spreadDeg <= 0.0) return axis
    val maxTan = tan(Math.toRadians(spreadDeg.coerceAtMost(179.0) / 2.0))
    val r = maxTan * sqrt(rng.nextDouble())
    val phi = rng.nextDouble() * 2.0 * Math.PI
    val helper = if (abs(axis.y) < 0.9) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)
    val a = axis.cross(helper).normalize()
    val b = axis.cross(a)
    return (axis + a * (r * cos(phi)) + b * (r * sin(phi))).normalize()
}

private fun particleColor(hex: String): ParticleColor? {
    val rgb = hex.removePrefix("#").take(6).toIntOrNull(16) ?: return null
    return ParticleColor((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
}
