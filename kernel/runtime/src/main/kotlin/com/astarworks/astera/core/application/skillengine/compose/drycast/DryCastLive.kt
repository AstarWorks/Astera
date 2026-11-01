package com.astarworks.astera.core.application.skillengine.compose.drycast

import com.astarworks.astera.core.application.skillengine.expr.cel.CelExprCompiler
import com.astarworks.astera.core.application.skillengine.expr.cel.CondBindings
import com.astarworks.astera.core.application.skillengine.expr.cel.V3Bindings
import com.astarworks.astera.core.application.skillengine.runtime.player.CastHandle
import com.astarworks.astera.core.application.skillengine.runtime.player.CellExprs
import com.astarworks.astera.core.application.skillengine.runtime.player.EntityQueryExprHost
import com.astarworks.astera.core.application.skillengine.runtime.player.EventBinding
import com.astarworks.astera.core.application.skillengine.runtime.player.LiveValueSource
import com.astarworks.astera.core.application.skillengine.runtime.player.PlayerHooks
import com.astarworks.astera.core.application.skillengine.runtime.player.RowScope
import com.astarworks.astera.core.application.skillengine.runtime.port.CastRef
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillEntityQuery
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnv
import com.astarworks.astera.core.domain.skillengine.expr.ExprHost
import com.astarworks.astera.core.domain.skillengine.expr.ExprType
import com.astarworks.astera.core.domain.skillengine.expr.ExprValue
import com.astarworks.astera.core.domain.skillengine.expr.FrameLookup
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.math.Quaterniond
import com.astarworks.astera.core.domain.skillengine.math.Vector3d
import com.astarworks.astera.core.domain.skillengine.model.CasterPose
import com.astarworks.astera.core.domain.skillengine.table.Guard
import com.astarworks.astera.core.domain.skillengine.table.TargetRule
import com.astarworks.astera.core.domain.skillengine.table.TargetSet
import com.astarworks.astera.core.domain.world.WorldId
import java.util.UUID

private val DRY_WORLD = WorldId("dry-cast")

/**
 * What the Paper host resolves for a caster standing at the origin, looking along [pose] (`PaperLiveValueSource`: the caster fields, contact /
 * victim bindings, the CEL cell forms). Anything the table player owns itself (an instance anchor, a param cell) is a named error here too.
 */
internal class StaticLiveValueSource(
    private val eyeHeight: Double,
    private val pose: CasterPose,
    private val caster: UUID,
    private val victims: List<HitCandidate>,
    private val query: ISkillEntityQuery,
) : LiveValueSource {
    private val cells = CellExprs()
    private val feet = Vec3(0.0, 0.0, 0.0)
    private val eye = Vec3(0.0, eyeHeight, 0.0)

    private fun paperResolvable(name: String): Boolean {
        if (name.startsWith("= ") || name.startsWith("expr:")) return true
        val bare = name.removePrefix("anchor:")
        val root = bare.substringBefore('.')
        if (root == "caster") return bare == "caster" || bare.removePrefix("caster.") in CASTER_FIELDS
        if (bare == "event.victim" || bare == "event.attacker") return true
        if (bare == "victim" || bare == "target" || bare.startsWith("victim.")) return true
        return bare == "contact" || (bare.contains('.') && (root == "contact" || root == "target"))
    }

    private fun paperRotatable(name: String): Boolean {
        if (name.startsWith("= ") || name.startsWith("expr:")) return true
        val bare = name.removePrefix("anchor:")
        return bare == "caster" || bare.startsWith("caster.") || bare == "contact" || bare == "victim" || bare == "target" || bare.startsWith("victim.")
    }

    private fun require(name: String, wanted: String) {
        if (if (wanted == "a rotation") !paperRotatable(name) else !paperResolvable(name)) {
            throw IllegalArgumentException("live cell '$name' is not resolvable as $wanted on Paper (instance anchors, param cells need the table player's own state)")
        }
    }

    private fun castRef(cast: CastHandle) = CastRef(cast.value, caster, DRY_WORLD, null)

    private fun victimOf(binding: EventBinding?): HitCandidate? = binding?.victim?.id?.let { id -> victims.firstOrNull { it.entityId == id } }

    private fun victimFrame(binding: EventBinding?, point: String?): Frame? {
        val c = victimOf(binding) ?: return null
        val o = when (point) {
            null, "body" -> c.box.center
            "feet", "root" -> c.feet
            "eye" -> Vec3(c.feet.x, c.box.max.y - 0.1, c.feet.z)
            "center" -> c.center
            else -> return null
        }
        return Frame.fromYawPitch(o, c.yawDeg, 0.0)
    }

    private fun victimBinding(binding: EventBinding?): V3Bindings.Binding {
        val v = victimOf(binding) ?: return V3Bindings.Binding()
        val dir = Frame.fromYawPitch(v.feet, v.yawDeg, 0.0).forward()
        return V3Bindings.Binding(pos = v.feet, forward = dir, eye = Vec3(v.feet.x, v.feet.y + 1.62, v.feet.z))
    }

    private fun contactBinding(binding: EventBinding?): V3Bindings.Binding {
        val c = binding?.contact ?: return V3Bindings.Binding()
        val point = Vec3(c.point.x, c.point.y, c.point.z)
        return V3Bindings.Binding(pos = point, normal = Vec3(c.normal.x, c.normal.y, c.normal.z), point = point)
    }

    private fun cell(cast: CastHandle, name: String, binding: EventBinding?, scope: RowScope): ExprValue {
        val casterFrame = Frame.fromYawPitch(feet, pose.yawDeg, 0.0)
        val inst = scope.frames
        val frames = object : FrameLookup {
            override fun worldPose(frame: String, point: String?): Frame? = when {
                frame == "contact" -> binding?.contact?.let { c ->
                    com.astarworks.astera.core.domain.skillengine.frame.ContactBasis.frame(
                        Vec3(c.point.x, c.point.y, c.point.z), Vec3(c.normal.x, c.normal.y, c.normal.z), casterFrame.forward(),
                    )
                }
                frame == "victim" || frame == "target" -> victimOf(binding)?.let { v ->
                    val origin = when (point) {
                        null, "body" -> Vec3(v.feet.x, v.feet.y + 0.9, v.feet.z)
                        "feet", "root" -> v.feet
                        "eye" -> Vec3(v.feet.x, v.feet.y + 1.62, v.feet.z)
                        "center" -> v.center
                        else -> null
                    }
                    origin?.let { Frame.fromYawPitch(it, v.yawDeg, 0.0) }
                }
                frame == "caster" -> when (point) {
                    null, "feet" -> casterFrame
                    "eye" -> Frame.fromYawPitch(eye, pose.yawDeg, pose.pitchDeg)
                    "look" -> Frame.fromYawPitch(feet, pose.yawDeg, pose.pitchDeg)
                    "center" -> Frame.fromYawPitch(Vec3(0.0, eyeHeight / 2.0, 0.0), pose.yawDeg, 0.0)
                    else -> casterFrame
                }
                else -> inst?.worldPose(frame, point)
            }
            override fun velocity(frame: String, point: String?) = if (frame == "caster") Vec3(0.0, 0.0, 0.0) else inst?.velocity(frame, point) ?: Vec3(0.0, 0.0, 0.0)
        }
        val ref = castRef(cast)
        val world = EntityQueryExprHost(query, ref)
        val host = object : ExprHost {
            override fun aimPoint(caster: ExprValue.Ref, range: Double): Vec3 {
                val d = Frame.fromYawPitch(eye, pose.yawDeg, pose.pitchDeg).forward()
                return Vec3(eye.x + d.x * range, eye.y + d.y * range, eye.z + d.z * range)
            }
            override fun lookDirection(caster: ExprValue.Ref): Vec3 = Frame.fromYawPitch(feet, pose.yawDeg, pose.pitchDeg).forward()
            override fun stacks(entity: ExprValue.Ref, status: String): Double = 0.0
            override fun hasItem(entity: ExprValue.Ref, item: String): Boolean = true
            override fun nearestEnemyDist(from: Vec3): Double = world.nearestEnemyDist(from)
            override fun enemyInCone(from: Vec3, dir: Vec3, range: Double, halfAngleDeg: Double): Boolean = world.enemyInCone(from, dir, range, halfAngleDeg)
        }
        val vars = V3Bindings.values(null, castTick = 0, eventI = binding?.index ?: 0, contact = contactBinding(binding), target = victimBinding(binding))
        return cells.eval(name, scope, vars, frames, host)
    }

    private fun casterVec(bare: String): Vector3d? {
        if (bare != "caster" && !bare.startsWith("caster.")) return null
        val hands = setOf("hand_r", "hand_l", "palm")
        val v = when (val field = bare.removePrefix("caster").removePrefix(".")) {
            "", "feet", "pos", "position", "look" -> feet
            "eye" -> eye
            "center" -> Vec3(0.0, eyeHeight / 2.0, 0.0)
            "body" -> Vec3(0.0, 0.9, 0.0)
            "forward" -> Frame.fromYawPitch(feet, pose.yawDeg, pose.pitchDeg).forward()
            in hands -> {
                val yawFrame = Frame.fromYawPitch(feet, pose.yawDeg, 0.0)
                val side = if (field == "hand_l") 1.0 else -1.0
                val left = yawFrame.left()
                val fwd = yawFrame.forward()
                Vec3(left.x * side * 0.35 + fwd.x * 0.45, eyeHeight - 0.55, left.z * side * 0.35 + fwd.z * 0.45)
            }
            else -> throw IllegalArgumentException("caster field '$bare' is not one of feet, eye, center, body, look, forward, hand_r, hand_l, palm")
        }
        return Vector3d(v.x, v.y, v.z)
    }

    private fun bindingVec(name: String, binding: EventBinding?): Vector3d? {
        if (name == "contact") return contactBinding(binding).point.let { Vector3d(it.x, it.y, it.z) }
        if (name == "event.victim" || name == "event.attacker") {
            val at = if (name == "event.victim") victimOf(binding)?.feet else if (binding?.attacker == caster) feet else null
            at ?: throw IllegalArgumentException("'$name' needs a hit event binding")
            return Vector3d(at.x, at.y, at.z)
        }
        val root = name.substringBefore('.', "")
        if (root != "contact" && root != "target") return null
        val b = if (root == "contact") contactBinding(binding) else victimBinding(binding)
        val v = when (val field = name.substringAfter('.')) {
            "pos" -> b.pos
            "forward" -> b.forward
            "eye" -> b.eye
            "normal" -> if (root == "contact") b.normal else throw IllegalArgumentException("'$name': only contact has a normal")
            "point" -> if (root == "contact") b.point else throw IllegalArgumentException("'$name': only contact has a point")
            else -> throw IllegalArgumentException("binding field '$field' of '$name' is not one of pos, forward, eye, normal, point")
        }
        return Vector3d(v.x, v.y, v.z)
    }

    override fun vec(cast: CastHandle, name: String, binding: EventBinding?, scope: RowScope): Vector3d {
        require(name, "a vector")
        val bare = name.removePrefix("anchor:")
        casterVec(bare)?.let { return it }
        bindingVec(bare, binding)?.let { return it }
        if (CellExprs.sourceOf(name) != null) {
            return when (val v = cell(cast, name, binding, scope)) {
                is ExprValue.Vec3 -> Vector3d(v.value.x, v.value.y, v.value.z)
                is ExprValue.Num -> Vector3d(v.value, 0.0, 0.0)
                else -> throw IllegalArgumentException("live cell '$name' is not a vector")
            }
        }
        if (bare == "victim" || bare == "target" || bare.startsWith("victim.")) {
            val point = if (bare.contains('.')) bare.substringAfter('.') else null
            val o = (victimFrame(binding, point) ?: throw IllegalArgumentException("'$name' needs a hit event binding with a loaded victim")).origin
            return Vector3d(o.x, o.y, o.z)
        }
        return Vector3d(0.0, 0.0, 0.0)
    }

    override fun rot(cast: CastHandle, name: String, binding: EventBinding?, scope: RowScope): Quaterniond {
        require(name, "a rotation")
        val bare = name.removePrefix("anchor:")
        if (bare == "caster" || bare.startsWith("caster.")) {
            val pitched = bare == "caster.look" || bare == "caster.eye" || bare.removePrefix("caster.") in setOf("hand_r", "hand_l", "palm")
            val q = Frame.fromYawPitch(feet, pose.yawDeg, if (pitched) pose.pitchDeg else 0.0).rotation
            return Quaterniond(q.x, q.y, q.z, q.w)
        }
        if (CellExprs.sourceOf(name) != null) {
            val v = cell(cast, name, binding, scope) as? ExprValue.Quat ?: throw IllegalArgumentException("live cell '$name' is not a rotation")
            return Quaterniond(v.value.x, v.value.y, v.value.z, v.value.w)
        }
        if (bare == "contact") {
            val c = binding?.contact ?: throw IllegalArgumentException("live cell '$name' is not resolvable as a rotation (no contact bound)")
            val q = com.astarworks.astera.core.domain.skillengine.frame.ContactBasis.frame(
                Vec3(c.point.x, c.point.y, c.point.z), Vec3(c.normal.x, c.normal.y, c.normal.z), Frame.fromYawPitch(feet, pose.yawDeg, 0.0).forward(),
            ).rotation
            return Quaterniond(q.x, q.y, q.z, q.w)
        }
        if (bare == "victim" || bare == "target" || bare.startsWith("victim.")) {
            val point = if (bare.contains('.')) bare.substringAfter('.') else null
            val r = (victimFrame(binding, point) ?: throw IllegalArgumentException("'$name' needs a bound victim")).rotation
            return Quaterniond(r.x, r.y, r.z, r.w)
        }
        return Quaterniond()
    }

    override fun num(cast: CastHandle, name: String, binding: EventBinding?, scope: RowScope): Double {
        if (CellExprs.sourceOf(name) == null) return 0.0
        return when (val v = cell(cast, name, binding, scope)) {
            is ExprValue.Num -> v.value
            is ExprValue.Bool -> if (v.value) 1.0 else 0.0
            else -> throw IllegalArgumentException("live cell '$name' is not a number")
        }
    }

    override fun targets(cast: CastHandle, rule: TargetRule, binding: EventBinding?): TargetSet {
        val ref = (rule as? TargetRule.Entity)?.ref ?: throw IllegalArgumentException("target rule $rule has no dry-cast resolver")
        if (ref != "event.victim") throw IllegalArgumentException("target rule '$ref' is resolved by the player, not the live source")
        val victim = binding?.victim ?: throw IllegalArgumentException("target 'event.victim' needs a hit event binding with a victim")
        return TargetSet(listOf(victim))
    }

    private companion object {
        val CASTER_FIELDS = setOf("feet", "pos", "position", "look", "eye", "body", "forward")
    }
}

/** Guards evaluate with the other party two blocks ahead of the caster; a guard that cannot evaluate answers null (the player logs a fault and treats it as false). */
internal class DryCastHooks(private val query: ISkillEntityQuery, private val caster: UUID) : PlayerHooks {
    private val compiler = CelExprCompiler()

    override fun guard(cast: CastHandle, guard: Guard, conditions: Map<String, String>, binding: EventBinding?, localTick: Int, seed: Long): Boolean? {
        val source = when (guard) {
            is Guard.Expr -> guard.source
            is Guard.Named -> "cond.${guard.cond}"
        }
        val expanded = (CondBindings.expand("dry-cast", "guard", source, conditions) as? Result.Success)?.value ?: return null
        val expr = (compiler.compile(expanded, V3Bindings.spec(null), ExprType.BOOL) as? Result.Success)?.value ?: return null
        val ahead = Vec3(0.0, 0.0, 2.0)
        val vars = V3Bindings.values(null, stateTick = localTick, eventI = binding?.index ?: 0, target = V3Bindings.Binding(pos = ahead))
        val world = EntityQueryExprHost(query, CastRef(cast.value, caster, DRY_WORLD, null))
        val host = object : ExprHost {
            override fun aimPoint(caster: ExprValue.Ref, range: Double): Vec3 = error("aim_point is not served by the dry-cast guard host")
            override fun lookDirection(caster: ExprValue.Ref): Vec3 = error("look_dir is not served by the dry-cast guard host")
            override fun hasItem(entity: ExprValue.Ref, item: String): Boolean = true
            override fun nearestEnemyDist(from: Vec3): Double = world.nearestEnemyDist(from)
            override fun enemyInCone(from: Vec3, dir: Vec3, range: Double, halfAngleDeg: Double): Boolean = world.enemyInCone(from, dir, range, halfAngleDeg)
        }
        val frames = object : FrameLookup {
            override fun worldPose(frame: String, point: String?) = if (frame == "caster") Frame(Vec3(0.0, 0.0, 0.0)) else Frame(ahead)
            override fun velocity(frame: String, point: String?) = Vec3(0.0, 0.0, 0.0)
        }
        return try { (expr.eval(ExprEnv(vars, frames, seed = seed, host = host)) as? ExprValue.Bool)?.value } catch (e: RuntimeException) { null }
    }

    override fun lock(caster: UUID, move: com.astarworks.astera.core.domain.skillengine.table.LockMove, pitch: Boolean, yaw: Boolean, untilTick: Int): Boolean = true

    override fun setDisplayField(handle: com.astarworks.astera.core.application.skillengine.runtime.port.VisualHandle, field: String, value: Double): Boolean = true

    override fun setDisplayText(handle: com.astarworks.astera.core.application.skillengine.runtime.port.VisualHandle, field: String, value: String): Boolean = true
}
