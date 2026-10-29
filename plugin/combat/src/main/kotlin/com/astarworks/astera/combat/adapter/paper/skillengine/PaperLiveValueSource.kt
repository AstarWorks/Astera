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
import com.astarworks.astera.core.application.skillengine.runtime.player.EntityQueryExprHost
import com.astarworks.astera.core.application.skillengine.runtime.port.CastRef
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillEntityQuery
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
    private val query: ISkillEntityQuery,
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
        casterSamples.keys.removeIf { it.first !in live }
    }

    public fun envSpec(): ExprEnvSpec = spec

    /** The CEL host of [cast] at the event [binding]: `stacks`, `nearest_enemy_dist`, `enemy_in_cone` (guards and live cells share it). */
    public fun exprHost(cast: CastHandle, binding: EventBinding?): ExprHost = StackHost(request(cast), cast, binding)

    /** The CEL variables of [cast] at the event [binding] (`contact.*`, `target.*`, `cast.t`, `state.t`). */
    public fun variables(cast: CastHandle, binding: EventBinding?, castTick: Int, stateTick: Int): Map<String, ExprValue> =
        V3Bindings.values(
            occurrence = null,
            castTick = castTick,
            stateTick = stateTick,
            eventI = binding?.index ?: 0,
            contact = contactOf(binding),
            target = victimOf(binding),
            extra = entityState("caster", server.getEntity(request(cast).caster) as? LivingEntity) +
                entityState("target", binding?.victim?.id?.let { server.getEntity(it) as? LivingEntity }),
        )

    /** `<root>.on_ground` / `<root>.health_ratio` (3.8 conditions); absent entity = the neutral values of [V3Bindings.values]. */
    private fun entityState(root: String, e: LivingEntity?): Map<String, ExprValue> {
        if (e == null || !e.isValid) return emptyMap()
        val max = e.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: 20.0
        return mapOf(
            "$root.on_ground" to ExprValue.Bool(e.isOnGround),
            "$root.health_ratio" to ExprValue.Num(if (max > 0.0) (e.health / max).coerceIn(0.0, 1.0) else 0.0),
        )
    }

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
                    in HANDS -> Frame.fromYawPitch(handOrigin(p, point.orEmpty()), p.yaw, p.pitch)
                    else -> null
                }
            }

            override fun velocity(frame: String, point: String?): Vec3 {
                if (frame != CASTER) return Vec3.ZERO
                val p = casterPose(request)
                val now = when (point) {
                    null, "feet", "look" -> p.feet
                    "eye" -> p.eye
                    in HANDS -> handOrigin(p, point.orEmpty())
                    else -> return Vec3.ZERO
                }
                return casterVelocity(cast, point ?: "", now)
            }
        }
    }

    private class Sample(var tick: Long, var pos: Vec3, var velocity: Vec3)

    private val casterSamples = ConcurrentHashMap<Pair<CastHandle, String>, Sample>()

    /**
     * Velocity (blocks per tick) of a caster-anchored point from the pose of the previous sampled tick: `(pos(now) - pos(last)) / ticks between`.
     * The first sample of a cast answers ZERO; asking again within the same tick repeats the answer.
     */
    private fun casterVelocity(cast: CastHandle, point: String, now: Vec3): Vec3 {
        val tick = scheduler.currentTick()
        val key = cast to point
        val s = casterSamples[key]
        if (s == null) {
            casterSamples[key] = Sample(tick, now, Vec3.ZERO)
            return Vec3.ZERO
        }
        if (tick == s.tick) return s.velocity
        val dt = (tick - s.tick).coerceAtLeast(1L).toDouble()
        s.velocity = Vec3((now.x - s.pos.x) / dt, (now.y - s.pos.y) / dt, (now.z - s.pos.z) / dt)
        s.pos = now
        s.tick = tick
        return s.velocity
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
            val pitched = bare == "$CASTER.look" || bare == "$CASTER.eye" || bare.removePrefix("$CASTER.") in HANDS
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

    /**
     * The caster's hand point (`hand_r`, `hand_l`, `palm` = the right palm): below the eye, to the side and in front of the body along the
     * yaw heading, so objects glued to it ride the arm the item is held in. One calibrated rest-pose offset, not the animated arm.
     */
    private fun handOrigin(p: CasterPose, point: String): Vec3 {
        val yawFrame = Frame.fromYawPitch(p.feet, p.yaw, 0.0)
        val side = if (point == "hand_l") 1.0 else -1.0
        val drop = (p.eye.y - p.feet.y) - HAND_BELOW_EYE
        val left = yawFrame.left()
        val fwd = yawFrame.forward()
        return Vec3(p.feet.x + left.x * side * HAND_SIDE + fwd.x * HAND_FORWARD, p.feet.y + drop, p.feet.z + left.z * side * HAND_SIDE + fwd.z * HAND_FORWARD)
    }

    private fun casterVec(request: CastRequest, name: String): Vector3d? {
        if (name != CASTER && !name.startsWith("$CASTER.")) return null
        val p = casterPose(request)
        val v = when (name.removePrefix(CASTER).removePrefix(".")) {
            "", "feet", "pos", "position", "look" -> p.feet
            "eye" -> p.eye
            "body" -> p.body
            "forward" -> Frame.fromYawPitch(p.feet, p.yaw, p.pitch).forward()
            in HANDS -> handOrigin(p, name.removePrefix("$CASTER."))
            else -> throw IllegalArgumentException("caster field '$name' is not one of feet, eye, body, look, forward, hand_r, hand_l, palm")
        }
        return Vector3d(v.x, v.y, v.z)
    }

    private fun bindingVec(name: String, binding: EventBinding?): Vector3d? {
        if (name == "event.victim" || name == "event.attacker") return eventEntityVec(name, binding)
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

    /** `event.victim` / `event.attacker` as a point: the bound entity's feet. */
    private fun eventEntityVec(name: String, binding: EventBinding?): Vector3d {
        val id = (if (name == "event.victim") binding?.victim?.id else binding?.attacker)
            ?: throw IllegalArgumentException("'$name' needs a hit event binding")
        val loc = (server.getEntity(id) ?: throw IllegalArgumentException("'$name': entity $id is not loaded")).location
        return Vector3d(loc.x, loc.y, loc.z)
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
            host = StackHost(request, cast, binding),
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
    private inner class StackHost(private val request: CastRequest, cast: CastHandle, private val binding: EventBinding?) : ExprHost {
        private val world = EntityQueryExprHost(query, CastRef(cast.value, request.caster, request.world, request.skill.def.item))

        override fun nearestEnemyDist(from: Vec3): Double = world.nearestEnemyDist(from)

        override fun enemyInCone(from: Vec3, dir: Vec3, range: Double, halfAngleDeg: Double): Boolean = world.enemyInCone(from, dir, range, halfAngleDeg)

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
        val HANDS = setOf("hand_r", "hand_l", "palm")
        const val HAND_BELOW_EYE = 0.55
        const val HAND_SIDE = 0.35
        const val HAND_FORWARD = 0.45
    }
}
