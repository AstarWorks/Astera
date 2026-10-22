package com.astarworks.astera.core.domain.effect.vfx

import com.astarworks.astera.core.domain.effect.DisplayTransform
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import kotlin.math.roundToInt

/**
 * Colour / alpha written to a quad's TextDisplay: solid quads use the background ARGB, glyph quads the
 * text colour + `text_opacity`. [textRgb] is `null` for solid quads (the text is a space).
 */
public data class VfxQuadStyle(
    public val backgroundArgb: Int,
    /** 0..255 (client treats < 4 as opaque, so glyph quads never go below [VfxQuad.MIN_TEXT_OPACITY]). */
    public val textOpacity: Int,
    public val textRgb: Int?,
)

/**
 * Display-particle quad maths ("display particles": coloured translucent quads on a TextDisplay).
 *
 * Minecraft 26.3 TextDisplayRenderer applies Ry(180), scale(-.025), then
 * translation(1 - textWidth/2, -9, 0) for one line. A space (advance 4) has
 * background bounds [-1,4] x [-1,9], hence local centre (+.0125,+.125,0)
 * and size .125 x .25 blocks. Confirmed with a real-client calibration capture.
 * The default 8px glyph cell uses the same centre; its size is .2 blocks.
 *
 * Particle position is carried by the entity, so the transformation compensates
 * the native centre BEFORE rotation/scale. A camera-facing quad must not also
 * inherit caster yaw (VfxTimeline handles that separately).

 */
public object VfxQuad {
    public const val SOLID_SCALE_X: Double = 8.0
    public const val SOLID_SCALE_Y: Double = 4.0
    public const val GLYPH_SCALE: Double = 5.0

    /** Native one-line text centre above/right of the entity origin, per display scale. */
    public const val CENTER_X_PER_SCALE: Double = 0.0125
    public const val CENTER_Y_PER_SCALE: Double = 0.125

    /** Client treats `text_opacity` below 4 as opaque. */
    public const val MIN_TEXT_OPACITY: Int = 4

    /** Display `scale` for a quad of [size] blocks (x = width, y = height). */
    public fun displayScale(kind: VfxElementKind.Quad, size: Vec3): Vec3 =
        if (kind.glyph == null) {
            Vec3(size.x * SOLID_SCALE_X, size.y * SOLID_SCALE_Y, size.z)
        } else {
            Vec3(size.x * GLYPH_SCALE, size.y * GLYPH_SCALE, size.z)
        }

    /** Transformation of a quad of [size] blocks facing [rotation]; its box centre lands on the entity position. */
    public fun transform(kind: VfxElementKind.Quad, rotation: Quaternion, size: Vec3): DisplayTransform {
        val scale = displayScale(kind, size)
        return DisplayTransform(rotation.rotate(Vec3(-CENTER_X_PER_SCALE * scale.x, -CENTER_Y_PER_SCALE * scale.y, 0.0)), rotation, scale)
    }

    public fun style(kind: VfxElementKind.Quad, rgb: Int, alpha: Double): VfxQuadStyle {
        val byte = (alpha.coerceIn(0.0, 1.0) * 255.0).roundToInt()
        return if (kind.glyph == null) {
            VfxQuadStyle((byte shl 24) or (rgb and 0xFFFFFF), 255, null)
        } else {
            VfxQuadStyle(0, byte.coerceAtLeast(MIN_TEXT_OPACITY), rgb and 0xFFFFFF)
        }
    }

    /** The quad's text: a space (background-only box) or the glyph. */
    public fun text(kind: VfxElementKind.Quad): String = kind.glyph ?: " "

    /** Linear RGB interpolation of two `0xRRGGBB` colours. */
    public fun lerpRgb(a: Int, b: Int, f: Double): Int {
        fun ch(shift: Int): Int {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            return (x + (y - x) * f).roundToInt().coerceIn(0, 255)
        }
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Colour + alpha of the element's keyframe track at element-relative tick [u] (carry-over, eased like transforms). */
    public fun trackAt(el: VfxElement, kind: VfxElementKind.Quad, u: Double): Pair<Int, Double> {
        var color = kind.colorRgb
        var alpha = kind.alpha
        val resolved = el.keyframes.map { f ->
            color = f.color ?: color
            alpha = f.alpha ?: alpha
            Triple(f, color, alpha)
        }
        if (resolved.isEmpty()) return kind.colorRgb to kind.alpha
        val first = resolved.first()
        if (u <= first.first.t) return first.second to first.third
        val last = resolved.last()
        if (u >= last.first.t) return last.second to last.third
        val k = resolved.indexOfFirst { it.first.t >= u }
        val a = resolved[k - 1]
        val b = resolved[k]
        val f = b.first.ease.apply((u - a.first.t) / (b.first.t - a.first.t))
        return lerpRgb(a.second, b.second, f) to (a.third + (b.third - a.third) * f)
    }

    /** Whether the element's keyframes animate colour or alpha. */
    public fun hasStyleTrack(el: VfxElement): Boolean = el.keyframes.any { it.color != null || it.alpha != null }
}
