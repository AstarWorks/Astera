package com.astarworks.astera.core.application.skillengine.load.ops

import com.astarworks.astera.core.application.skillengine.load.OpDecoder
import com.astarworks.astera.core.application.skillengine.load.OpReader
import com.astarworks.astera.core.domain.skillengine.hit.HitVolume
import com.astarworks.astera.core.domain.skillengine.hit.HitWindow
import com.astarworks.astera.core.domain.skillengine.model.AxisRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.HitOp
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.op.SkillOp

/**
 * `op: hit`: `frames`, exactly one of `capsule {from,to,radius}` / `sphere {at,radius}` /
 * `sector {frame,forward,left,angle_deg,range,height}`, and `window: once | per_part | {every_ticks: N}`.
 * `self.root` / `self.tip` name points of each listed frame.
 */
public class HitOpDecoders : OpDecoder {
    override val section: OpSection = OpSection.GAMEPLAY
    override val opNames: Set<String> = setOf("hit")

    override fun decode(op: String, id: String, whenExpr: Value<Boolean>?, reader: OpReader): SkillOp =
        HitOp.Hit(
            id = id,
            whenExpr = whenExpr,
            frames = reader.stringList("frames") ?: reader.fail("missing 'frames'"),
            volume = volume(reader),
            window = window(reader),
        )

    private fun volume(reader: OpReader): HitVolume {
        val capsule = reader.raw("capsule")
        val sphere = reader.raw("sphere")
        val sector = reader.raw("sector")
        val given = listOf(capsule, sphere, sector).count { it != null }
        if (given != 1) reader.fail("hit needs exactly one of capsule / sphere / sector (found $given)")
        return when {
            capsule != null -> {
                val m = FrameRawValues.map(capsule, reader, "capsule")
                FrameRawValues.onlyKeys(m, setOf("from", "to", "radius"), reader, "capsule")
                HitVolume.Capsule(
                    from = FrameRawValues.point(m["from"], reader, "capsule.from"),
                    to = FrameRawValues.point(m["to"], reader, "capsule.to"),
                    radius = FrameRawValues.num(m["radius"] ?: reader.fail("capsule: missing 'radius'"), reader, "capsule.radius"),
                )
            }
            sphere != null -> {
                val m = FrameRawValues.map(sphere, reader, "sphere")
                FrameRawValues.onlyKeys(m, setOf("at", "radius"), reader, "sphere")
                HitVolume.Sphere(
                    at = FrameRawValues.point(m["at"], reader, "sphere.at"),
                    radius = FrameRawValues.num(m["radius"] ?: reader.fail("sphere: missing 'radius'"), reader, "sphere.radius"),
                )
            }
            else -> {
                val m = FrameRawValues.map(sector, reader, "sector")
                FrameRawValues.onlyKeys(m, setOf("frame", "forward", "left", "angle_deg", "range", "height"), reader, "sector")
                fun req(key: String): Any = m[key] ?: reader.fail("sector: missing '$key'")
                HitVolume.Sector(
                    frame = req("frame") as? String ?: reader.fail("sector.frame: expected a frame name"),
                    forward = AxisRef(req("forward") as? String ?: reader.fail("sector.forward: expected an axis ref")),
                    left = AxisRef(req("left") as? String ?: reader.fail("sector.left: expected an axis ref")),
                    angleDeg = FrameRawValues.num(req("angle_deg"), reader, "sector.angle_deg"),
                    range = FrameRawValues.num(req("range"), reader, "sector.range"),
                    height = FrameRawValues.num(req("height"), reader, "sector.height"),
                )
            }
        }
    }

    private fun window(reader: OpReader): HitWindow {
        return when (val raw = reader.raw("window")) {
            null, "once" -> HitWindow.Once
            "per_part" -> HitWindow.PerPart
            is Map<*, *> -> {
                val m = FrameRawValues.map(raw, reader, "window")
                FrameRawValues.onlyKeys(m, setOf("every_ticks"), reader, "window")
                val n = (m["every_ticks"] as? Number)?.toInt() ?: reader.fail("window: 'every_ticks' must be an integer")
                if (n < 1) reader.fail("window.every_ticks must be >= 1")
                HitWindow.EveryTicks(n)
            }
            else -> reader.fail("window: expected once, per_part or {every_ticks: N}")
        }
    }
}
