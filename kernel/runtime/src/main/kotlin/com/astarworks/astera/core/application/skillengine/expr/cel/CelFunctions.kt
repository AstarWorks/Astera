package com.astarworks.astera.core.application.skillengine.expr.cel

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.geometry.math.SeededRandom
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnv
import com.astarworks.astera.core.domain.skillengine.expr.ExprHost
import com.astarworks.astera.core.domain.skillengine.expr.ExprEvalException
import com.astarworks.astera.core.domain.skillengine.expr.ExprValue
import dev.cel.common.CelFunctionDecl
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

    private fun worldPose(r: ExprValue.Ref): Frame = env().frames.worldPose(r.name, r.point)
        ?: throw ExprEvalException("unknown frame or point '${r.name}${r.point?.let { ".$it" } ?: ""}'")

    private fun axisOf(f: Frame, which: String): Vec3 = when (which) {
        "forward" -> f.forward()
        "up" -> f.up()
        "left" -> f.left()
        "right" -> f.right()
        "back" -> f.forward() * -1.0
        "down" -> f.up() * -1.0
        else -> throw ExprEvalException("axis name must be forward|back|up|down|left|right, got '$which'")
    }

    private fun stream(name: String): SeededRandom =
        streams.get().getOrPut(name) { SeededRandom(env().seed xor (name.hashCode().toLong() * -0x61c8864680b583ebL)) }

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

        // frames
        def("pos", "pos_ref", VEC3, listOf(REF), listOf(R)) { worldPose(it[0] as ExprValue.Ref).origin }
        def("axis", "axis_ref", VEC3, listOf(REF, SimpleType.STRING), listOf(R, STR)) { axisOf(worldPose(it[0] as ExprValue.Ref), it[1] as String) }
        def("point", "point_ref", REF, listOf(REF, SimpleType.STRING), listOf(R, STR)) { (it[0] as ExprValue.Ref).copy(point = it[1] as String) }
        def("vel", "vel_ref", VEC3, listOf(REF), listOf(R)) { (it[0] as ExprValue.Ref).let { r -> env().frames.velocity(r.name, r.point) } }
        def("speed", "speed_ref", D, listOf(REF), listOf(R)) { (it[0] as ExprValue.Ref).let { r -> env().frames.velocity(r.name, r.point).length() } }

        // world axes of a frame (same as axis(f, "left") ...)
        for (name in listOf("forward", "up", "left", "right")) {
            def(name, "${name}_ref", VEC3, listOf(REF), listOf(R)) { axisOf(worldPose(it[0] as ExprValue.Ref), name) }
        }

        // cast-level queries, served by the cast through ExprEnv.host
        def("aim_point", "aim_point_ref_num", VEC3, listOf(REF, NUM), listOf(R, OBJ)) {
            host().aimPoint(it[0] as ExprValue.Ref, n(it[1]))
        }
        def("blade_len", "blade_len_ref", D, listOf(REF), listOf(R)) { host().bladeLen(it[0] as ExprValue.Ref) }

        def("counter", "counter_str", D, listOf(SimpleType.STRING), listOf(STR)) { host().counter(it[0] as String) }

        // spatial tests (angles in degrees)
        def("dist", "dist_any", D, listOf(NUM, NUM), listOf(OBJ, OBJ)) { vec(it[0]).distance(vec(it[1])) }
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
