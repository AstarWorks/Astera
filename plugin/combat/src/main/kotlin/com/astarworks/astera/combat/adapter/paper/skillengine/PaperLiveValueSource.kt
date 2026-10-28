package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.skillengine.expr.cel.CelExprCompiler
import com.astarworks.astera.core.application.skillengine.expr.cel.V3Bindings
import com.astarworks.astera.core.application.skillengine.runtime.CastRequest
import com.astarworks.astera.core.application.skillengine.runtime.player.CastHandle
import com.astarworks.astera.core.application.skillengine.runtime.player.EventBinding
import com.astarworks.astera.core.application.skillengine.runtime.player.RowScope
import com.astarworks.astera.core.application.skillengine.runtime.player.LiveValueSource
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillGameplayGateway
import com.astarworks.astera.core.domain.skillengine.expr.ExprEvalException
import com.astarworks.astera.core.domain.skillengine.expr.ExprHost
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.frame.ContactBasis
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.expr.CompiledExpr
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnv
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnvSpec
import com.astarworks.astera.core.domain.skillengine.expr.ExprValue
import com.astarworks.astera.core.domain.skillengine.expr.FrameLookup
import com.astarworks.astera.core.domain.skillengine.table.EntityRef
import com.astarworks.astera.core.domain.skillengine.table.TargetRule
import com.astarworks.astera.core.domain.skillengine.table.TargetSet
import org.bukkit.Server
import org.bukkit.entity.LivingEntity
import com.astarworks.astera.core.domain.skillengine.math.Quaterniond
import com.astarworks.astera.core.domain.skillengine.math.Vector3d
import java.util.concurrent.ConcurrentHashMap

/**
 * Paper [LiveValueSource]: resolves `Val.Live` cells against the live caster and the event binding (ADR-0303 C4c).
 *
 * Names it serves: `caster[.feet|.eye|.forward|.rotation]` and `anchor:caster[...]`; `contact.*` / `target.*`; the
 * CEL forms `= <expr>` and `expr:<expr>` (variables of [V3Bindings] plus the `caster` frame). Everything else (instance
 * anchors, `${p}` / `param:` cells, motion turns) is answered with an [IllegalArgumentException] naming it: the table
 * player turns that into a fault, so an unresolved cell is loud, never a silent zero.
 */
public class PaperLiveValueSource(
    private val server: Server,
    private val scheduler: IScheduler,
    private val gameplay: ISkillGameplayGateway,
) : LiveValueSource {
    private val requests = ConcurrentHashMap<CastHandle, CastRequest>()
    private val compiler = CelExprCompiler()
    private val compiled = ConcurrentHashMap<String, CompiledExpr>()
    private val spec: ExprEnvSpec = V3Bindings.spec(null, ExprEnvSpec(frames = setOf(CASTER)))

    public fun register(cast: CastHandle, request: CastRequest) {
        requests[cast] = request
    }

    public fun requestOf(cast: CastHandle): CastRequest? = requests[cast]

    /** Drops the casts that ended; [live] is the player's running set. */
    public fun retain(live: Set<CastHandle>) {
        requests.keys.retainAll(live)
    }

    public fun envSpec(): ExprEnvSpec = spec

    /** The CEL variables of [cast] at the event [binding] (`contact.*`, `target.*`, `cast.t`, `state.t`). */
    public fun variables(cast: CastHandle, binding: EventBinding?, castTick: Int, stateTick: Int): Map<String, ExprValue> =
        V3Bindings.values(
            occurrence = null,
            castTick = castTick,
            stateTick = stateTick,
            eventI = binding?.index ?: 0,
            contact = contactOf(binding),
            target = victimOf(binding),
        )

    public fun frames(cast: CastHandle, binding: EventBinding? = null): FrameLookup {
        val request = request(cast)
        return object : FrameLookup {
            override fun worldPose(frame: String, point: String?): Frame? {
                if (frame == "contact") {
                    val c = binding?.contact ?: return null
                    val p = casterPose(request)
                    val fwd = Frame.fromYawPitch(p.feet, p.yaw, p.pitch).forward()
                    return ContactBasis.frame(Vec3(c.point.x, c.point.y, c.point.z), Vec3(c.normal.x, c.normal.y, c.normal.z), fwd)
                }
                if (frame != CASTER) return null
                val p = casterPose(request)
                return when (point) {
                    // bare `caster` is yaw only, as v2 `CastInstance.casterLocal` (CastInstance.kt:210, 807 at 9d4c4a3db); pitch is `look` / `eye`
                    null, "feet" -> Frame.fromYawPitch(p.feet, p.yaw, 0.0)
                    "eye" -> Frame.fromYawPitch(p.eye, p.yaw, p.pitch)
                    "look" -> Frame.fromYawPitch(p.feet, p.yaw, p.pitch)
                    else -> null
                }
            }

            override fun velocity(frame: String, point: String?): Vec3 = Vec3.ZERO
        }
    }

    override fun vec(cast: CastHandle, name: String, binding: EventBinding?, scope: RowScope): Vector3d {
        val request = request(cast)
        val bare = name.removePrefix("anchor:")
        casterVec(request, bare)?.let { return it }
        bindingVec(bare, binding)?.let { return it }
        return when (val v = evalExpr(cast, request, name, binding, scope)) {
            is ExprValue.Vec3 -> Vector3d(v.value.x, v.value.y, v.value.z)
            is ExprValue.Num -> Vector3d(v.value, 0.0, 0.0)
            else -> unsupported(name, "a vector")
        }
    }

    override fun num(cast: CastHandle, name: String, binding: EventBinding?, scope: RowScope): Double {
        val request = request(cast)
        return when (val v = evalExpr(cast, request, name, binding, scope)) {
            is ExprValue.Num -> v.value
            is ExprValue.Bool -> if (v.value) 1.0 else 0.0
            else -> unsupported(name, "a number")
        }
    }

    override fun rot(cast: CastHandle, name: String, binding: EventBinding?, scope: RowScope): Quaterniond {
        val request = request(cast)
        val bare = name.removePrefix("anchor:")
        if (bare == CASTER || bare.startsWith("$CASTER.")) {
            val p = casterPose(request)
            val pitched = bare == "$CASTER.look" || bare == "$CASTER.eye"
            return quat(Frame.fromYawPitch(p.feet, p.yaw, if (pitched) p.pitch else 0.0).rotation)
        }
        return when (val v = evalExpr(cast, request, name, binding, scope)) {
            is ExprValue.Quat -> quat(v.value)
            else -> unsupported(name, "a rotation")
        }
    }

    override fun targets(cast: CastHandle, rule: TargetRule, binding: EventBinding?): TargetSet {
        val request = request(cast)
        return when (rule) {
            is TargetRule.Entity -> when (rule.ref) {
                "caster" -> TargetSet(listOf(EntityRef(request.caster)))
                "event.victim" -> TargetSet(listOfNotNull(binding?.victim))
                "event.attacker" -> TargetSet(listOfNotNull(binding?.attacker?.let { EntityRef(it) }))
                else -> throw IllegalArgumentException("target rule '${rule.ref}' has no Paper resolver")
            }
            is TargetRule.Area -> throw IllegalArgumentException("area target rule needs an anchor-instance resolver (not in LiveValueSource's Paper side yet)")
        }
    }

    // ------------------------------------------------------------------ caster / binding

    private class CasterPose(val feet: Vec3, val eye: Vec3, val body: Vec3, val yaw: Double, val pitch: Double)

    private fun casterPose(request: CastRequest): CasterPose {
        val e = server.getEntity(request.caster) as? LivingEntity
        if (e == null || !e.isValid) {
            val feet = request.casterFeet
            return CasterPose(feet, feet, feet, request.casterYawDeg, request.casterPitchDeg)
        }
        val loc = e.location
        // body = feet + hitbox height / 2, the v2 body centre (ConeSelector, v2 at 9d4c4a3db)
        return CasterPose(
            Vec3(loc.x, loc.y, loc.z), Vec3(loc.x, loc.y + e.eyeHeight, loc.z), Vec3(loc.x, loc.y + e.height / 2.0, loc.z),
            loc.yaw.toDouble(), loc.pitch.toDouble(),
        )
    }

    private fun casterVec(request: CastRequest, name: String): Vector3d? {
        if (name != CASTER && !name.startsWith("$CASTER.")) return null
        val p = casterPose(request)
        val v = when (name.removePrefix(CASTER).removePrefix(".")) {
            "", "feet", "pos", "position", "look" -> p.feet
            "eye" -> p.eye
            "body" -> p.body
            "forward" -> Frame.fromYawPitch(p.feet, p.yaw, p.pitch).forward()
            else -> throw IllegalArgumentException("caster field '$name' is not one of feet, eye, body, look, forward")
        }
        return Vector3d(v.x, v.y, v.z)
    }

    private fun bindingVec(name: String, binding: EventBinding?): Vector3d? {
        val root = name.substringBefore('.', "")
        if (root != "contact" && root != "target") return null
        val b = if (root == "contact") contactOf(binding) else victimOf(binding)
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

    private fun contactOf(binding: EventBinding?): V3Bindings.Binding {
        val c = binding?.contact ?: return V3Bindings.Binding()
        val point = Vec3(c.point.x, c.point.y, c.point.z)
        return V3Bindings.Binding(pos = point, normal = Vec3(c.normal.x, c.normal.y, c.normal.z), point = point)
    }

    private fun victimOf(binding: EventBinding?): V3Bindings.Binding {
        val id = binding?.victim?.id ?: return V3Bindings.Binding()
        val e = server.getEntity(id) as? LivingEntity ?: return V3Bindings.Binding()
        val loc = e.location
        val dir = loc.direction
        return V3Bindings.Binding(
            pos = Vec3(loc.x, loc.y, loc.z),
            forward = Vec3(dir.x, dir.y, dir.z),
            eye = Vec3(loc.x, loc.y + e.eyeHeight, loc.z),
        )
    }

    // ------------------------------------------------------------------ CEL cells

    private fun evalExpr(cast: CastHandle, request: CastRequest, name: String, binding: EventBinding?, scope: RowScope): ExprValue {
        val source = when {
            name.startsWith("= ") -> name.removePrefix("= ")
            name.startsWith("expr:") -> name.removePrefix("expr:")
            else -> unsupported(name, "a resolvable cell")
        }
        val expr = compiled.getOrPut(source) {
            when (val r = compiler.compile(source, spec, null)) {
                is Result.Success -> r.value
                is Result.Failure -> throw IllegalArgumentException("'$source' does not compile: ${r.error.message}")
            }
        }
        // the row's own seed and copy indices (R10), as the compiler folds with: so `rand()` and `i` read what game-sim reads
        val vars = LinkedHashMap(variables(cast, binding, castTick = scheduler.currentTick().toInt() - request.startTick, stateTick = 0))
        var prefix = ""
        for (idx in scope.indices.take(V3Bindings.MAX_PARENT_DEPTH + 1)) {
            vars[prefix + "i"] = ExprValue.Num(idx.toDouble())
            prefix += "parent."
        }
        val env = ExprEnv(
            variables = vars,
            frames = withInstances(frames(cast), scope.frames),
            seed = scope.seed,
            castSeed = scope.castSeed,
            host = StackHost(request, binding),
        )
        return expr.eval(env)
    }

    /** The caster / contact frames first, then the cast's own frame / projectile instances ([inst], from the row's scope). */
    private fun withInstances(base: FrameLookup, inst: FrameLookup?): FrameLookup {
        if (inst == null) return base
        return object : FrameLookup {
            override fun worldPose(frame: String, point: String?): Frame? = base.worldPose(frame, point) ?: inst.worldPose(frame, point)

            override fun velocity(frame: String, point: String?): Vec3 =
                if (base.worldPose(frame, point) != null) base.velocity(frame, point) else inst.velocity(frame, point)
        }
    }

    /** `stacks(entity, status)` reads the gateway; the other host reads are not served on Paper yet and fail loudly. */
    private inner class StackHost(private val request: CastRequest, private val binding: EventBinding?) : ExprHost {
        override fun stacks(entity: ExprValue.Ref, status: String): Double {
            val id = when (entity.name) {
                CASTER -> request.caster
                "victim", "target" -> binding?.victim?.id ?: throw ExprEvalException("stacks('$status'): '${entity.name}' needs a hit event binding")
                else -> throw ExprEvalException("stacks('$status'): entity '${entity.name}' is not caster, victim or target")
            }
            return gameplay.statusStacks(id, status).toDouble()
        }

        override fun aimPoint(caster: ExprValue.Ref, range: Double): Vec3 = throw ExprEvalException("aim_point is not served by the Paper live-value host")
        override fun lookDirection(caster: ExprValue.Ref): Vec3 = throw ExprEvalException("look_dir is not served by the Paper live-value host")
        override fun bladeLen(frame: ExprValue.Ref): Double = throw ExprEvalException("blade_len is not served by the Paper live-value host")
    }

    private fun request(cast: CastHandle): CastRequest =
        requests[cast] ?: throw IllegalArgumentException("cast ${cast.value} is not registered with the Paper live-value source")

    private fun quat(q: Quaternion): Quaterniond = Quaterniond(q.x, q.y, q.z, q.w)

    private fun unsupported(name: String, wanted: String): Nothing =
        throw IllegalArgumentException("live cell '$name' is not resolvable as $wanted on Paper (instance anchors, \${p} and param: cells need the table player's own state)")

    private companion object {
        const val CASTER = "caster"
    }
}
