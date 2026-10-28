package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.core.domain.effect.DisplayTransform
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.op.PlateFace

/**
 * Display transform maths of the skill visual gateway (TextDisplay quad calibrated against a real-client capture on MC 26.3).
 * Pure maths, no Bukkit, so the game-sim can assert the matrices the Paper gateway would send.
 */
public object SkillDisplayMath {
    public const val SOLID_SCALE_X: Double = 8.0
    public const val SOLID_SCALE_Y: Double = 4.0
    public const val GLYPH_SCALE: Double = 5.0
    public const val CENTER_X_PER_SCALE: Double = 0.0125
    public const val CENTER_Y_PER_SCALE: Double = 0.125

    /** Plate [width] x [height] blocks (width along the quad's local X, height along local Y). */
    public fun plateTransform(rotation: Quaternion, width: Double, height: Double): DisplayTransform {
        val sx = width * GLYPH_SCALE
        val sy = height * GLYPH_SCALE
        val centre = Vec3(-CENTER_X_PER_SCALE * sx, -CENTER_Y_PER_SCALE * sy, 0.0)
        return DisplayTransform(rotation.rotate(centre), rotation, Vec3(sx, sy, 1.0))
    }

    /** Line from [from] to [to], [thickness] blocks wide; [viewerEye] null = nobody near. */
    public fun lineTransform(from: Vec3, to: Vec3, thickness: Double, face: PlateFace, viewerEye: Vec3?): DisplayTransform {
        val rotation = PlateOrientation.forLine(from, to, face, viewerEye)
        val sx = from.distance(to) * SOLID_SCALE_X
        val sy = thickness * SOLID_SCALE_Y
        val centre = Vec3(-CENTER_X_PER_SCALE * sx, -CENTER_Y_PER_SCALE * sy, 0.0)
        return DisplayTransform(rotation.rotate(centre), rotation, Vec3(sx, sy, 1.0))
    }

    /** Columns of a trail segment's transformation matrix: travel [u], root-to-tip [v], [normal]; plus the translation. */
    public class RibbonColumns(public val col0: Vec3, public val col1: Vec3, public val col2: Vec3, public val shift: Vec3)

    public fun ribbonColumns(u: Vec3, v: Vec3, normal: Vec3): RibbonColumns {
        val col0 = u * SOLID_SCALE_X
        val col1 = v * SOLID_SCALE_Y
        return RibbonColumns(col0, col1, normal, col0 * -CENTER_X_PER_SCALE + col1 * -CENTER_Y_PER_SCALE)
    }
}
