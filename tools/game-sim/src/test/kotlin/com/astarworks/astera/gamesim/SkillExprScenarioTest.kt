package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.skillengine.expr.cel.CelExprCompiler
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.expr.CompiledExpr
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnv
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnvSpec
import com.astarworks.astera.core.domain.skillengine.expr.ExprError
import com.astarworks.astera.core.domain.skillengine.expr.ExprType
import com.astarworks.astera.core.domain.skillengine.expr.ExprValue
import com.astarworks.astera.core.domain.skillengine.expr.FrameLookup
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test

/**
 * SKL-08 / ADR-0301: the expression layer. Compiles the ten expressions couture-rapier-s2 coin 2 needs through the real
 * CEL compiler, asserts their values, and prints rough compile / eval timings.
 */
class SkillExprScenarioTest {

    private val volley = Frame(Vec3(10.0, 64.0, -2.0), Quaternion(0.0, 0.0, 0.0, 1.0)) // identity: forward +z, up +y, left +x

    private val frames = object : FrameLookup {
        override fun worldPose(frame: String, point: String?): Frame? = when (frame to point) {
            "volley" to null -> volley
            "blade_a" to "tip" -> Frame(Vec3(12.0, 65.0, 1.0), Quaternion(0.0, 0.0, 0.0, 1.0))
            "blade_a" to null -> Frame(Vec3(11.0, 65.0, 0.0), Quaternion(0.0, 0.0, 0.0, 1.0))
            else -> null
        }

        override fun velocity(frame: String, point: String?): Vec3 =
            if (frame == "blade_a" && point == "tip") Vec3(3.0, 4.0, 0.0) else Vec3.ZERO
    }

    private val spec = ExprEnvSpec(
        variables = mapOf(
            "t" to ExprType.NUM,
            "throw_range" to ExprType.NUM,
            "modifier" to ExprType.NUM,
        ),
        frames = setOf("volley", "blade_a"),
    )

    private fun env(t: Double = 5.0, seed: Long = 42L) = ExprEnv(
        variables = mapOf(
            "t" to ExprValue.Num(t),
            "throw_range" to ExprValue.Num(12.0),
            "modifier" to ExprValue.Num(0.5),
        ),
        frames = frames,
        seed = seed,
    )

    private val compiler = CelExprCompiler()

    private fun compile(src: String): CompiledExpr = when (val r = compiler.compile(src, spec)) {
        is Result.Success -> r.value
        is Result.Failure -> error("'$src' failed to compile: ${r.error}")
    }

    private fun num(src: String, env: ExprEnv = env()): Double = (compile(src).eval(env) as ExprValue.Num).value

    @Test
    fun `coin 2 expressions compile and evaluate`() {
        val sources = listOf(
            // 1 spin: horizontal axis (frame left), 54 deg per tick
            """= rotate(quat_axis_angle(axis(volley,"left"), t*54.0), axis(volley,"up"))""",
            // 2 whoosh pitch from tip speed
            """= speed(point(blade_a,"tip")) * 0.3 + 0.8""",
            // 3 modifier-scaled range via ${param}
            "= \${throw_range} * modifier",
            // 4 distance tip to volley origin, int literals mixed with doubles
            """= dist(point(blade_a,"tip"), volley) + 2""",
            // 5 vector arithmetic
            "= vec(1,2,3) + vec(1.0,1.0,1.0) * 2",
            // 6 cross / len / norm
            "= len(cross(vec(1,0,0), vec(0,3,0))) + dot(norm(vec(0,0,9)), vec(0,0,1))",
            // 7 spatial test
            """= in_arc(volley, vec(10.0, 64.0, 3.0), 30, 10)""",
            // 8 behind
            """= behind(volley, pos(volley) - vec(0,0,3))""",
            // 9 scalar maths
            "= clamp(t*2, 0, 10) + sqrt(16) + pow(2, 3) + deg(atan2(1, 1))",
            // 10 conditional string + comparison of double with int literal
            """= t > 2 ? "late" : "early"""",
        )
        val e = env()
        val compiled = sources.map { compile(it) }

        val q = Quaternion.axisAngle(Vec3(1.0, 0.0, 0.0), Math.toRadians(5.0 * 54.0))
        val expected1 = q.rotate(Vec3(0.0, 1.0, 0.0))
        val v1 = (compiled[0].eval(e) as ExprValue.Vec3).value
        assertThat(v1.x).isCloseTo(expected1.x, Offset.offset(1e-9))
        assertThat(v1.y).isCloseTo(expected1.y, Offset.offset(1e-9))
        assertThat(v1.z).isCloseTo(expected1.z, Offset.offset(1e-9))
        // spin axis stays perpendicular to the throw direction (forward = +z here): the rotated vector has no x component
        assertThat(v1.x).isCloseTo(0.0, Offset.offset(1e-9))

        assertThat((compiled[1].eval(e) as ExprValue.Num).value).isCloseTo(2.3, Offset.offset(1e-9))
        assertThat((compiled[2].eval(e) as ExprValue.Num).value).isCloseTo(6.0, Offset.offset(1e-9))
        assertThat((compiled[3].eval(e) as ExprValue.Num).value).isCloseTo(Vec3(2.0, 1.0, 3.0).length() + 2.0, Offset.offset(1e-9))
        assertThat((compiled[4].eval(e) as ExprValue.Vec3).value).isEqualTo(Vec3(3.0, 4.0, 5.0))
        assertThat((compiled[5].eval(e) as ExprValue.Num).value).isCloseTo(4.0, Offset.offset(1e-9))
        assertThat(compiled[6].eval(e)).isEqualTo(ExprValue.Bool(true))
        assertThat(compiled[7].eval(e)).isEqualTo(ExprValue.Bool(true))
        assertThat((compiled[8].eval(e) as ExprValue.Num).value).isCloseTo(10.0 + 4.0 + 8.0 + 45.0, Offset.offset(1e-9))
        assertThat(compiled[9].eval(e)).isEqualTo(ExprValue.Str("late"))
        assertThat(compiled[9].eval(env(t = 1.0))).isEqualTo(ExprValue.Str("early"))
    }

    @Test
    fun `rand is deterministic per cast seed`() {
        val a = num("""rand("spread")""", env(seed = 7))
        val b = num("""rand("spread")""", env(seed = 7))
        val c = num("""rand("spread")""", env(seed = 8))
        assertThat(a).isEqualTo(b)
        assertThat(a).isNotEqualTo(c)
        assertThat(a).isBetween(0.0, 1.0)
        // the stream advances within one cast, and is reset for the next eval of the same seed
        val twice = compile("""rand("spread") + rand("spread") * 10""")
        assertThat((twice.eval(env(seed = 7)) as ExprValue.Num).value).isEqualTo((twice.eval(env(seed = 7)) as ExprValue.Num).value)
    }

    @Test
    fun `errors carry a column`() {
        val syntax = compiler.compile("= 1 + * 2", spec)
        assertThat(syntax).isInstanceOf(Result.Failure::class.java)
        val se = (syntax as Result.Failure).error
        assertThat(se).isInstanceOf(ExprError.Syntax::class.java)
        assertThat(se.column).isGreaterThan(0)

        val type = compiler.compile("= no_such_var + 1", spec)
        val te = (type as Result.Failure).error
        assertThat(te).isInstanceOf(ExprError.Type::class.java)
        assertThat(te.column).isEqualTo(3)

        val wrong = (compiler.compile("""= pos(5)""", spec) as Result.Failure).error
        assertThat(wrong).isInstanceOf(ExprError.Type::class.java)
    }

    @Test
    fun `timings`() {
        val sources = listOf(
            """rotate(quat_axis_angle(axis(volley,"left"), t*54.0), axis(volley,"up"))""",
            """speed(point(blade_a,"tip")) * 0.3 + 0.8""",
            "\${throw_range} * modifier",
            """dist(point(blade_a,"tip"), volley) + 2""",
            "vec(1,2,3) + vec(1.0,1.0,1.0) * 2",
        )
        val t0 = System.nanoTime()
        val compiled = sources.map { compile(it) }
        val coldMs = (System.nanoTime() - t0) / 1e6
        val t1 = System.nanoTime()
        repeat(20) { sources.forEach { compile(it) } }
        val warmUs = (System.nanoTime() - t1) / 1e3 / (20 * sources.size)
        val e = env()
        repeat(20_000) { compiled.forEach { it.eval(e) } }
        val n = 100_000
        val t2 = System.nanoTime()
        repeat(n) { compiled.forEach { it.eval(e) } }
        val evalUs = (System.nanoTime() - t2) / 1e3 / (n * compiled.size)
        println("SKILL-EXPR-TIMING compile cold(5 exprs, first use incl. compiler build)=%.1f ms, warm=%.0f us/expr, eval=%.2f us/call".format(coldMs, warmUs, evalUs))
        assertThat(evalUs).isLessThan(200.0)
    }
}
