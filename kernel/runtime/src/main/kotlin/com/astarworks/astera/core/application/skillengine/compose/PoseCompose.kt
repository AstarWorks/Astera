package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.domain.skillengine.math.Quaterniond
import com.astarworks.astera.core.domain.skillengine.math.Vector3d
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.Pose
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.Val

/**
 * A `Key` row is a whole pose (ADR-0303 3), so the law rows of an instance that has its own authored base pose (a frame's `offset` / `turn`,
 * a display's `offset` / `rotate` / `scale`) must carry that base or the first key overwrites it: `offset = base + law`, `rot = base * law`,
 * `scale = base * law`. A Known base folds; a live OFFSET base (`offset: = ...`) becomes a live sum cell. A live rotation or scale base cannot be re-based and fails the load;
 * a reactive law row (follow / align / stretch, live pose) owns its channels and is left as authored; a live align carries the instance's own turn after it (see [AlignCell]).
 */
internal object PoseCompose {
    fun composeBase(
        rows: List<Row>, position: Val<Vector3d>, rotation: Val<Quaterniond>, scale: Val<Vector3d>, fail: (String) -> Nothing,
    ): List<Row> {
        val bp = (position as? Val.Known)?.value
        val br = (rotation as? Val.Known)?.value
        val bs = (scale as? Val.Known)?.value
        val identity = bp != null && br != null && bs != null && bp.lengthSquared() == 0.0 &&
            br.x == 0.0 && br.y == 0.0 && br.z == 0.0 && bs.x == 1.0 && bs.y == 1.0 && bs.z == 1.0
        if (identity) return rows
        return rows.map { row ->
            val k = row.action as? Action.Key ?: return@map row
            val t = k.translation
            val r = k.leftRotation
            val s = k.scale
            if (t !is Val.Known || r !is Val.Known || s !is Val.Known) {
                // a reactive row (follow / align / stretch) owns its own channels and is left as authored there; a channel it does not own
                // (an align leaves the offset) still takes the instance's own literal offset, so a lane offset survives the live turn
                // the instance's own turn rides on a live align as a numeric turn after it (the blade's tilt inside the upright volley)
                var out = k
                if (t is Val.Known && bp != null) out = out.copy(translation = Val.Known(Vector3d(bp).add(t.value)))
                if (r is Val.Live && AlignCell.isAlign(r.name) && br != null) out = out.copy(leftRotation = Val.Live(AlignCell.withPost(r.name, br), null))
                // the instance's own literal scale survives a live turn too: without this an `align` key resets a 0.06 x 0.06 x 0.9 fin to a 1-block cube
                if (s is Val.Known && bs != null) out = out.copy(scale = Val.Known(Vector3d(bs.x * s.value.x, bs.y * s.value.y, bs.z * s.value.z)))
                return@map if (out === k) row else row.copy(action = out)
            }
            if (br == null) fail("a motion on a live rotation cannot be re-based onto the instance's own rotate/turn")
            if (bs == null) fail("a motion on a live scale cannot be re-based onto the instance's own scale")
            val translation: Val<Vector3d> = when {
                bp != null -> Val.Known(Vector3d(bp).add(t.value))
                position is Val.Live && position.name.startsWith("= ") ->
                    Val.Live("= (${position.name.removePrefix("= ")}) + vec(${t.value.x}, ${t.value.y}, ${t.value.z})", null)
                else -> fail("a motion on a live offset cannot be re-based unless the offset is a '= ' expression")
            }
            row.copy(
                action = k.copy(
                    translation = translation,
                    leftRotation = Val.Known(Quaterniond(br).mul(r.value)),
                    scale = Val.Known(Vector3d(bs.x * s.value.x, bs.y * s.value.y, bs.z * s.value.z)),
                ),
            )
        }
    }

    /**
     * [outer] then [inner]: the pose of something authored in [inner] on a parent whose own authored pose is [outer] (a rider of a moving frame
     * skips the frame, so its spawn pose and keys carry the frame's base). Known poses fold; an identity side returns the other; a live
     * side against a non-identity side fails (the chain cannot be flattened).
     */
    fun combine(outer: Pose, inner: Pose, fail: (String) -> Nothing): Pose {
        if (isIdentity(outer)) return inner
        if (isIdentity(inner)) return outer
        val op = (outer.position as? Val.Known)?.value
        val ip = (inner.position as? Val.Known)?.value
        val or = (outer.rotation as? Val.Known)?.value
        val ir = (inner.rotation as? Val.Known)?.value
        val os = (outer.scale as? Val.Known)?.value
        val isc = (inner.scale as? Val.Known)?.value
        // a component needs both sides literal only when neither side is that component's identity: an identity side returns the other side unchanged (even a live one)
        val position: Val<Vector3d> = when {
            op != null && op.lengthSquared() == 0.0 -> inner.position
            ip != null && ip.lengthSquared() == 0.0 -> outer.position
            op != null && ip != null -> Val.Known(Vector3d(op).add(ip))
            else -> fail("a rider of a moving frame needs a literal offset on both the frame and the rider (an expression cannot be re-based onto the frame pose)")
        }
        val rotation: Val<Quaterniond> = when {
            or != null && or.x == 0.0 && or.y == 0.0 && or.z == 0.0 -> inner.rotation
            ir != null && ir.x == 0.0 && ir.y == 0.0 && ir.z == 0.0 -> outer.rotation
            or != null && ir != null -> Val.Known(Quaterniond(or).mul(ir))
            else -> fail("a rider of a moving frame needs a literal turn / rotate on both the frame and the rider (an expression cannot be re-based onto the frame pose)")
        }
        val scale: Val<Vector3d> = when {
            os != null && os.x == 1.0 && os.y == 1.0 && os.z == 1.0 -> inner.scale
            isc != null && isc.x == 1.0 && isc.y == 1.0 && isc.z == 1.0 -> outer.scale
            os != null && isc != null -> Val.Known(Vector3d(os.x * isc.x, os.y * isc.y, os.z * isc.z))
            else -> fail("a rider of a moving frame needs a literal scale on both the frame and the rider (an expression cannot be re-based onto the frame pose)")
        }
        return Pose(position, rotation, scale)
    }

    fun isIdentity(p: Pose): Boolean {
        val bp = (p.position as? Val.Known)?.value
        val br = (p.rotation as? Val.Known)?.value
        val bs = (p.scale as? Val.Known)?.value
        return bp != null && br != null && bs != null && bp.lengthSquared() == 0.0 &&
            br.x == 0.0 && br.y == 0.0 && br.z == 0.0 && bs.x == 1.0 && bs.y == 1.0 && bs.z == 1.0
    }
}
