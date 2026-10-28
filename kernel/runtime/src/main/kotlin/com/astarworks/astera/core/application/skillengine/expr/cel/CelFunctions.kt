package com.astarworks.astera.core.application.skillengine.expr.cel

import com.astarworks.astera.core.domain.geometry.math.Basis
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.geometry.math.SeededRandom
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnv
import com.astarworks.astera.core.domain.skillengine.expr.ExprHost
import com.astarworks.astera.core.domain.skillengine.expr.ExprEvalException
import com.astarworks.astera.core.domain.skillengine.expr.ExprValue
import dev.cel.common.CelFunctionDecl
import dev.cel.common.ast.CelConstant
import dev.cel.common.ast.CelExpr
import dev.cel.common.CelOverloadDecl
import dev.cel.common.types.CelType
import dev.cel.common.types.OpaqueType
import dev.cel.common.types.SimpleType
import dev.cel.runtime.CelFunctionBinding
import dev.cel.runtime.CelFunctionOverload
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Opaque-type outcome (ADR-0301): `vec3`, `quat` and `ref` are CEL OpaqueTypes whose runtime values are the kernel
 * [Vec3] / [Quaternion] / [ExprValue.Ref] objects themselves. Operators are overloads of `_+_` etc. No list fallback needed.
 *
 * Scalars: every numeric function takes `dyn` so integer literals (`2`) and doubles (`2.0`) both work; results are doubles.
 */
internal object CelFunctions {
    val VEC3: CelType = OpaqueType.create("vec3")
    val QUAT: CelType = OpaqueType.create("quat")
    val REF: CelType = OpaqueType.create("ref")
    private val NUM = SimpleType.DYN
    private val D = SimpleType.DOUBLE
    private val I = SimpleType.INT

    /** Frame queries and `rand` need the live env; set for the duration of one eval (thread-confined, region-thread safe). */
    private val current: ThreadLocal<ExprEnv> = ThreadLocal()
    private val streams: ThreadLocal<MutableMap<String, SeededRandom>> = ThreadLocal.withInitial { HashMap() }

    fun beginEval(env: ExprEnv) {
        current.set(env)
        streams.get().clear()
    }

    fun endEval() {
        current.remove()
    }

    private fun env(): ExprEnv = current.get() ?: throw ExprEvalException("expression evaluated outside CompiledExpr.eval")

    private fun host(): ExprHost = env().host ?: throw ExprEvalException("this expression needs a cast (aim_point / blade_len)")

    private fun n(o: Any?): Double = (o as? Number)?.toDouble() ?: throw ExprEvalException("expected a number, got $o")

    private fun vec(o: Any?): Vec3 = when (o) {
        is Vec3 -> o
        is ExprValue.Ref -> worldPose(o).origin
        else -> throw ExprEvalException("expected a vec3 or frame, got $o")
    }

    /** Distance helpers measure feet to feet: a frame operand is its feet (the `victim` frame's pose is its body centre, so its `feet` point), a vec3 is used as given. */
    private fun feet(o: Any?): Vec3 = when (o) {
        is Vec3 -> o
        is ExprValue.Ref ->
            if (o.name == "victim" && o.point == null) worldPose(o.copy(point = "feet")).origin else worldPose(o).origin
        else -> throw ExprEvalException("expected a vec3 or frame, got $o")
    }

    private fun worldPose(r: ExprValue.Ref): Frame = env().frames.worldPose(r.name, r.point)
        ?: throw ExprEvalException("unknown frame or point '${r.name}${r.point?.let { ".$it" } ?: ""}'")

    private fun axisOf(f: Frame, which: String): Vec3 = Basis.axis(f, which)
        ?: throw ExprEvalException("axis name must be forward|back|up|down|left|right, got '$which'")

    private const val GOLDEN = -0x61c8864680b583ebL

    /** Advancing stream: per cast, tick and replay sample, fresh at every eval. */
    private fun stream(name: String): SeededRandom =
        streams.get().getOrPut(name) { SeededRandom(env().seed xor (name.hashCode().toLong() * GOLDEN) xor (env().sample.toLong() * 0x2545F4914F6CDD1DL)) }

    /** Stateless value keyed like [stream] (cast, tick, replay sample, name) but fresh at every call, so it repeats within an eval. */
    private fun rolled(name: String): SeededRandom =
        SeededRandom(env().seed xor (name.hashCode().toLong() * GOLDEN) xor (env().sample.toLong() * 0x2545F4914F6CDD1DL) xor 0x1B873593L xor occurrenceKey())

    /** Mixes the copy index chain (`i`, `parent.i`, ...) so the copies of a `times` row roll different values (R10); 0 when none is bound. */
    private fun occurrenceKey(): Long {
        var h = 0L
        var prefix = ""
        repeat(V3Bindings.MAX_PARENT_DEPTH + 1) {
            val i = (env().variables[prefix + "i"] as? ExprValue.Num)?.value ?: return h
            h = (h xor i.toLong()) * 0x2545F4914F6CDD1DL + 0x9E3779B9L
            prefix += "parent."
        }
        return h
    }

    /** Stateless value keyed by the cast seed and [name] only: the same on every call, tick and sample of one cast. */
    private fun drawn(name: String): SeededRandom = SeededRandom(env().castSeed xor (name.hashCode().toLong() * GOLDEN) xor 0x5DEECE66DL)

    /** v1 `PlaneBasis`: the two unit vectors perpendicular to [axis] and each other. */
    private fun planeA(axis: Vec3): Vec3 {
        val u = axis.normalize()
        val p = if (abs(u.x) > abs(u.y)) Vec3(-u.y, u.x, 0.0) else Vec3(0.0, -u.z, u.y)
        return u.cross(p).normalize()
    }

    private class Def(
        val name: String,
        val id: String,
        val result: CelType,
        val params: List<CelType>,
        val classes: List<Class<*>>,
        val fn: (Array<Any?>) -> Any?,
    )

    private val defs = ArrayList<Def>()

    private fun def(name: String, id: String, result: CelType, params: List<CelType>, classes: List<Class<*>>, fn: (Array<Any?>) -> Any?) {
        defs += Def(name, id, result, params, classes, fn)
    }

    private val OBJ: Class<*> = Any::class.java
    private val V: Class<*> = Vec3::class.java
    private val Q: Class<*> = Quaternion::class.java
    private val R: Class<*> = ExprValue.Ref::class.java
    private val STR: Class<*> = String::class.java
    private val DBL: Class<*> = java.lang.Double::class.java
    private val LNG: Class<*> = java.lang.Long::class.java

    private fun scalar1(name: String, f: (Double) -> Double) = def(name, "${name}_num", D, listOf(NUM), listOf(OBJ)) { f(n(it[0])) }
    private fun scalar2(name: String, f: (Double, Double) -> Double) =
        def(name, "${name}_num_num", D, listOf(NUM, NUM), listOf(OBJ, OBJ)) { f(n(it[0]), n(it[1])) }

    init {
        // vectors
        def("vec", "vec_num3", VEC3, listOf(NUM, NUM, NUM), listOf(OBJ, OBJ, OBJ)) { Vec3(n(it[0]), n(it[1]), n(it[2])) }
        def("dot", "dot_vec3", D, listOf(VEC3, VEC3), listOf(V, V)) { (it[0] as Vec3).dot(it[1] as Vec3) }
        def("cross", "cross_vec3", VEC3, listOf(VEC3, VEC3), listOf(V, V)) { (it[0] as Vec3).cross(it[1] as Vec3) }
        def("len", "len_vec3", D, listOf(VEC3), listOf(V)) { (it[0] as Vec3).length() }
        def("norm", "norm_vec3", VEC3, listOf(VEC3), listOf(V)) { (it[0] as Vec3).normalize() }
        // spec names (territories.md): `normalize` and `length` are the authoring spellings of norm / len
        def("normalize", "normalize_vec3", VEC3, listOf(VEC3), listOf(V)) { (it[0] as Vec3).normalize() }
        def("length", "length_vec3", D, listOf(VEC3), listOf(V)) { (it[0] as Vec3).length() }
        // the horizontal part of a vector (y dropped): a radial push is the flat direction plus its own vertical lift
        def("flat", "flat_vec3", VEC3, listOf(VEC3), listOf(V)) { (it[0] as Vec3).let { v -> Vec3(v.x, 0.0, v.z) } }
        // numbers: any int/double mix, result double (vectors have their own overload below)
        for (tag in listOf("dd", "di", "id", "ii")) {
            val pa = if (tag[0] == 'd') D else I
            val pb = if (tag[1] == 'd') D else I
            val ca = if (tag[0] == 'd') DBL else LNG
            val cb = if (tag[1] == 'd') DBL else LNG
            def("lerp", "lerp_num_$tag", D, listOf(pa, pb, NUM), listOf(ca, cb, OBJ)) { n(it[0]) + (n(it[1]) - n(it[0])) * n(it[2]) }
        }
        def("ease", "ease_str_num", D, listOf(SimpleType.STRING, NUM), listOf(STR, OBJ)) {
            val name = it[0] as String
            (EaseCurves.parse(name) ?: throw ExprEvalException("unknown easing '$name'"))(n(it[1]))
        }
        def("lerp", "lerp_vec3", VEC3, listOf(VEC3, VEC3, NUM), listOf(V, V, OBJ)) {
            val a = it[0] as Vec3
            val b = it[1] as Vec3
            a + (b - a) * n(it[2])
        }
        def("_+_", "add_vec3_vec3", VEC3, listOf(VEC3, VEC3), listOf(V, V)) { (it[0] as Vec3) + (it[1] as Vec3) }
        def("_-_", "sub_vec3_vec3", VEC3, listOf(VEC3, VEC3), listOf(V, V)) { (it[0] as Vec3) - (it[1] as Vec3) }
        def("-_", "neg_vec3", VEC3, listOf(VEC3), listOf(V)) { (it[0] as Vec3) * -1.0 }
        def("_*_", "mul_vec3_double", VEC3, listOf(VEC3, D), listOf(V, DBL)) { (it[0] as Vec3) * n(it[1]) }
        def("_*_", "mul_double_vec3", VEC3, listOf(D, VEC3), listOf(DBL, V)) { (it[1] as Vec3) * n(it[0]) }
        def("_*_", "mul_vec3_int", VEC3, listOf(VEC3, I), listOf(V, LNG)) { (it[0] as Vec3) * n(it[1]) }
        def("_*_", "mul_int_vec3", VEC3, listOf(I, VEC3), listOf(LNG, V)) { (it[1] as Vec3) * n(it[0]) }
        def("_/_", "div_vec3_double", VEC3, listOf(VEC3, D), listOf(V, DBL)) { (it[0] as Vec3) * (1.0 / n(it[1])) }
        def("_/_", "div_vec3_int", VEC3, listOf(VEC3, I), listOf(V, LNG)) { (it[0] as Vec3) * (1.0 / n(it[1])) }

        // mixed int/double arithmetic so authors can write `t * 2`
        val arith: List<Triple<String, String, (Double, Double) -> Double>> = listOf(
            Triple("_+_", "add", { a, b -> a + b }),
            Triple("_-_", "sub", { a, b -> a - b }),
            Triple("_*_", "mul", { a, b -> a * b }),
            Triple("_/_", "div", { a, b -> a / b }),
        )
        for ((op, tag, f) in arith) {
            def(op, "${tag}_double_int", D, listOf(D, I), listOf(DBL, LNG)) { f(n(it[0]), n(it[1])) }
            def(op, "${tag}_int_double", D, listOf(I, D), listOf(LNG, DBL)) { f(n(it[0]), n(it[1])) }
        }
        val compare: List<Triple<String, String, (Double, Double) -> Boolean>> = listOf(
            Triple("_<_", "lt", { a, b -> a < b }),
            Triple("_<=_", "le", { a, b -> a <= b }),
            Triple("_>_", "gt", { a, b -> a > b }),
            Triple("_>=_", "ge", { a, b -> a >= b }),
        )
        for ((op, tag, f) in compare) {
            def(op, "${tag}_double_int", SimpleType.BOOL, listOf(D, I), listOf(DBL, LNG)) { f(n(it[0]), n(it[1])) }
            def(op, "${tag}_int_double", SimpleType.BOOL, listOf(I, D), listOf(LNG, DBL)) { f(n(it[0]), n(it[1])) }
        }

        // quaternions
        def("quat_axis_angle", "quat_axis_angle", QUAT, listOf(VEC3, NUM), listOf(V, OBJ)) {
            Quaternion.axisAngle((it[0] as Vec3).normalize(), Math.toRadians(n(it[1])))
        }
        def("quat", "quat_rotvec", QUAT, listOf(VEC3), listOf(V)) { Quaternion.fromRotationVector(it[0] as Vec3) }
        // Rotation vector (axis * degrees, shortest way round) of a quaternion: the form a track `turn` is authored in.
        def("rotvec", "rotvec_quat", VEC3, listOf(QUAT), listOf(Q)) {
            val q = it[0] as Quaternion
            val s = if (q.w < 0) -1.0 else 1.0
            val x = q.x * s; val y = q.y * s; val z = q.z * s
            val sin = sqrt(x * x + y * y + z * z)
            if (sin < 1e-12) Vec3(0.0, 0.0, 0.0) else {
                val deg = Math.toDegrees(2.0 * atan2(sin, q.w * s))
                Vec3(x / sin * deg, y / sin * deg, z / sin * deg)
            }
        }
        def("rotate", "rotate_quat_vec3", VEC3, listOf(QUAT, VEC3), listOf(Q, V)) { (it[0] as Quaternion).rotate(it[1] as Vec3) }
        def("slerp", "slerp_quat", QUAT, listOf(QUAT, QUAT, NUM), listOf(Q, Q, OBJ)) {
            Quaternion.slerp(it[0] as Quaternion, it[1] as Quaternion, n(it[2]))
        }
        def("_*_", "mul_quat_quat", QUAT, listOf(QUAT, QUAT), listOf(Q, Q)) { (it[0] as Quaternion) * (it[1] as Quaternion) }
        def("_*_", "mul_quat_vec3", VEC3, listOf(QUAT, VEC3), listOf(Q, V)) { (it[0] as Quaternion).rotate(it[1] as Vec3) }

        // scalar maths (radians for trig; use deg()/rad() to convert)
        scalar1("sin", ::sin)
        scalar1("cos", ::cos)
        scalar1("tan", ::tan)
        scalar1("asin", ::asin)
        scalar1("acos", ::acos)
        scalar1("atan", ::atan)
        scalar2("atan2", ::atan2)
        scalar1("sqrt", ::sqrt)
        scalar1("abs", ::abs)
        scalar1("ceil", ::ceil)
        scalar1("floor", ::floor)
        scalar2("min") { a, b -> min(a, b) }
        scalar2("max") { a, b -> max(a, b) }
        scalar2("pow") { a, b -> a.pow(b) }
        def("clamp", "clamp_num", D, listOf(NUM, NUM, NUM), listOf(OBJ, OBJ, OBJ)) { n(it[0]).coerceIn(n(it[1]), n(it[2])) }
        def("mix", "mix_num", D, listOf(NUM, NUM, NUM), listOf(OBJ, OBJ, OBJ)) { n(it[0]) + (n(it[1]) - n(it[0])) * n(it[2]) }
        scalar1("deg") { it * 180.0 / PI }
        scalar1("rad") { it * PI / 180.0 }

        // seeded randomness: uniform [0,1), deterministic per cast seed + stream name
        def("rand", "rand_stream", D, listOf(SimpleType.STRING), listOf(STR)) { stream(it[0] as String).nextDouble() }
        // `roll*`: stateless, keyed by cast seed + tick + replay sample + name (a v1 draw made per play / per trail sample)
        def("roll", "roll_stream", D, listOf(SimpleType.STRING), listOf(STR)) { rolled(it[0] as String).nextDouble() }
        def("roll", "roll_stream_range", D, listOf(SimpleType.STRING, NUM, NUM), listOf(STR, OBJ, OBJ)) {
            rolled(it[0] as String).nextDouble(n(it[1]), n(it[2]))
        }
        def("roll_unit", "roll_unit_stream", VEC3, listOf(SimpleType.STRING), listOf(STR)) { rolled(it[0] as String).nextUnitVector() }
        // `draw*`: stateless, keyed by cast seed + name only: the same on every call, tick and sample of one cast (a v1 draw made once per cast)
        def("draw", "draw_stream", D, listOf(SimpleType.STRING), listOf(STR)) { drawn(it[0] as String).nextDouble() }
        def("draw", "draw_stream_range", D, listOf(SimpleType.STRING, NUM, NUM), listOf(STR, OBJ, OBJ)) {
            drawn(it[0] as String).nextDouble(n(it[1]), n(it[2]))
        }
        def("draw_unit", "draw_unit_stream", VEC3, listOf(SimpleType.STRING), listOf(STR)) { drawn(it[0] as String).nextUnitVector() }
        def("plane_a", "plane_a_vec3", VEC3, listOf(VEC3), listOf(V)) { planeA(it[0] as Vec3) }
        def("plane_b", "plane_b_vec3", VEC3, listOf(VEC3), listOf(V)) { (it[0] as Vec3).normalize().cross(planeA(it[0] as Vec3)).normalize() }
        // rand(stream, k): the k-th draw of a stream, constant over the cast (not per tick) - a per-slot jitter like v1 slotRandom
        def("rand", "rand_stream_idx", D, listOf(SimpleType.STRING, NUM), listOf(STR, OBJ)) {
            SeededRandom(env().castSeed xor ((it[0] as String).hashCode().toLong() * -0x61c8864680b583ebL) xor (n(it[1]).toLong() * 0x2545F4914F6CDD1DL)).nextDouble()
        }

        // frames
        // a bare `victim` is its feet (SKL-15), like `dist`; any named point of it (`point(victim, "body")`) is the body centre
        def("pos", "pos_ref", VEC3, listOf(REF), listOf(R)) { feet(it[0]) }
        def("axis", "axis_ref", VEC3, listOf(REF, SimpleType.STRING), listOf(R, STR)) { axisOf(worldPose(it[0] as ExprValue.Ref), it[1] as String) }
        def("point", "point_ref", REF, listOf(REF, SimpleType.STRING), listOf(R, STR)) { (it[0] as ExprValue.Ref).copy(point = it[1] as String) }
        def("velocity", "velocity_ref", VEC3, listOf(REF), listOf(R)) { (it[0] as ExprValue.Ref).let { r -> env().frames.velocity(r.name, r.point) } }
        def("speed", "speed_ref", D, listOf(REF), listOf(R)) { (it[0] as ExprValue.Ref).let { r -> env().frames.velocity(r.name, r.point).length() } }

        // world axes of a frame (same as axis(f, "left") ...)
        for (name in listOf("forward", "up", "left", "right")) {
            def(name, "${name}_ref", VEC3, listOf(REF), listOf(R)) { axisOf(worldPose(it[0] as ExprValue.Ref), name) }
        }

        // cast-level queries, served by the cast through ExprEnv.host
        def("aim_point", "aim_point_ref_num", VEC3, listOf(REF, NUM), listOf(R, OBJ)) {
            host().aimPoint(it[0] as ExprValue.Ref, n(it[1]))
        }
        def("look", "look_ref", VEC3, listOf(REF), listOf(R)) { host().lookDirection(it[0] as ExprValue.Ref) }
        def("blade_len", "blade_len_ref", D, listOf(REF), listOf(R)) { host().bladeLen(it[0] as ExprValue.Ref) }

        // world queries about the caster's surroundings (cast required)
        def("nearest_enemy_dist", "nearest_enemy_dist_ref", D, listOf(REF), listOf(R)) { host().nearestEnemyDist(feet(it[0])) }
        def("enemy_in_cone", "enemy_in_cone_ref_num_num", SimpleType.BOOL, listOf(REF, NUM, NUM), listOf(R, OBJ, OBJ)) {
            val ref = it[0] as ExprValue.Ref
            host().enemyInCone(feet(ref), axisOf(worldPose(ref), "forward"), n(it[1]), n(it[2]))
        }
        def("hp_ratio", "hp_ratio_ref", D, listOf(REF), listOf(R)) { host().hpRatio(it[0] as ExprValue.Ref) }
        def("counter", "counter_str", D, listOf(SimpleType.STRING), listOf(STR)) { host().counter(it[0] as String) }

        // spatial tests (angles in degrees)
        def("dist", "dist_any", D, listOf(NUM, NUM), listOf(OBJ, OBJ)) { feet(it[0]).distance(feet(it[1])) }
        def("angle", "angle_vec3", D, listOf(VEC3, VEC3), listOf(V, V)) {
            val a = (it[0] as Vec3).normalize()
            val b = (it[1] as Vec3).normalize()
            Math.toDegrees(acos(a.dot(b).coerceIn(-1.0, 1.0)))
        }
        def("in_arc", "in_arc_ref", SimpleType.BOOL, listOf(REF, VEC3, NUM, NUM), listOf(R, V, OBJ, OBJ)) {
            val f = worldPose(it[0] as ExprValue.Ref)
            val d = (it[1] as Vec3) - f.origin
            val len = d.length()
            len <= n(it[3]) && (len == 0.0 || Math.toDegrees(acos(f.forward().dot(d.normalize()).coerceIn(-1.0, 1.0))) <= n(it[2]))
        }
        def("behind", "behind_ref", SimpleType.BOOL, listOf(REF, VEC3), listOf(R, V)) {
            val f = worldPose(it[0] as ExprValue.Ref)
            f.forward().dot((it[1] as Vec3) - f.origin) < 0.0
        }
    }

    /**
     * Load-time checks that need argument values, not just types: the name of `ease(name, t)` must be a string
     * literal naming a known curve (ADR-0303 3.7). Returns the first problem, or null.
     */
    fun checkStatic(e: CelExpr): String? {
        when (e.getKind()) {
            CelExpr.ExprKind.Kind.CALL -> {
                val call = e.call()
                if (call.function() == "ease") {
                    val first = call.args().firstOrNull()
                    val name = first
                        ?.takeIf { it.getKind() == CelExpr.ExprKind.Kind.CONSTANT && it.constant().getKind() == CelConstant.Kind.STRING_VALUE }
                        ?.constant()?.stringValue()
                        ?: return "ease(name, t): the name must be a string literal"
                    if (EaseCurves.parse(name) == null) return "ease: unknown easing '$name' (ADR-0303 3.7)"
                }
                call.target().orElse(null)?.let { t -> checkStatic(t)?.let { return it } }
                for (a in call.args()) checkStatic(a)?.let { return it }
            }
            CelExpr.ExprKind.Kind.SELECT -> return checkStatic(e.select().operand())
            CelExpr.ExprKind.Kind.LIST -> for (a in e.list().elements()) checkStatic(a)?.let { return it }
            else -> {}
        }
        return null
    }

    fun declarations(): List<CelFunctionDecl> =
        defs.groupBy { it.name }.map { (name, list) ->
            CelFunctionDecl.newFunctionDeclaration(
                name,
                list.map { CelOverloadDecl.newGlobalOverload(it.id, it.result, it.params) },
            )
        }

    fun bindings(): List<CelFunctionBinding> = defs.map { d ->
        CelFunctionBinding.from(d.id, d.classes, CelFunctionOverload { args -> d.fn(args) })
    }
}
