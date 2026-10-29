package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.skillengine.expr.cel.CelExprCompiler
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.Every
import com.astarworks.astera.core.application.skillengine.load.grammar.Field
import com.astarworks.astera.core.application.skillengine.load.grammar.FrameVec
import com.astarworks.astera.core.application.skillengine.load.grammar.FrameVecs
import com.astarworks.astera.core.application.skillengine.load.grammar.Fields
import com.astarworks.astera.core.application.skillengine.load.grammar.Grammar
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.load.grammar.Rotation
import com.astarworks.astera.core.application.skillengine.load.grammar.Rotations
import com.astarworks.astera.core.application.skillengine.load.grammar.SchemaJson
import com.astarworks.astera.core.application.skillengine.load.grammar.Span
import com.astarworks.astera.core.application.skillengine.load.grammar.TimeAt
import com.astarworks.astera.core.application.skillengine.load.grammar.Timing
import com.astarworks.astera.core.application.skillengine.load.grammar.TimingKind
import com.astarworks.astera.core.application.skillengine.load.grammar.TimingSpec
import com.astarworks.astera.core.application.skillengine.load.grammar.Types
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnvSpec
import com.astarworks.astera.core.domain.skillengine.expr.ExprType
import com.astarworks.astera.core.domain.skillengine.model.ParamValue
import com.astarworks.astera.core.domain.skillengine.model.Value
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

/**
 * SKL-20 / E4 / ADR-0303 sections 0, 2.0, 3, 11: the shared value grammar. YAML snippets go through the real grammar
 * (Fields DSL, named vectors, axis-angle rotation, timing block, `${param}` / CEL via the real CelExprCompiler); asserts the
 * decoded domain values, the load errors (file + path + field) and the JSON-schema fragment emitted from the same spec.
 */
class GrammarFieldsScenarioTest {

    private val tol = Offset.offset(1e-9)
    private val cel = CelExprCompiler()
    private val ctx = GrammarContext(
        file = "couture-rapier-s2.yaml",
        params = mapOf("reach" to ParamValue.Num(2.0), "turns" to ParamValue.Num(3.0)),
        exprs = cel,
        env = ExprEnvSpec(variables = mapOf("reach" to ExprType.NUM, "turns" to ExprType.NUM, "i" to ExprType.NUM)),
    )

    private fun yaml(text: String): Any? = Yaml().load<Any?>(text.trimIndent())

    private fun <T> ok(r: Result<T, SkillDecodeError>): T = (r as? Result.Success)?.value
        ?: error("expected success but got ${(r as Result.Failure).error}")

    private fun <T> err(r: Result<T, SkillDecodeError>): SkillDecodeError = (r as? Result.Failure)?.error
        ?: error("expected a load error but decoded ${(r as Result.Success).value}")

    private fun assertQuat(actual: Quaternion, expected: Quaternion) {
        assertThat(actual.x).isCloseTo(expected.x, tol)
        assertThat(actual.y).isCloseTo(expected.y, tol)
        assertThat(actual.z).isCloseTo(expected.z, tol)
        assertThat(actual.w).isCloseTo(expected.w, tol)
    }

    private fun decodeVec(text: String): FrameVec = ok(Grammar.guard {
        FrameVecs.type.decode(yaml(text), ctx, "anchors[0].offset")
    })

    // ---------------------------------------------------------------- named vectors

    @Test
    fun `named vector is the local triple of the domain Vec3, missing axis is zero`() {
        val v = decodeVec("{forward: 2, up: 0.5}")
        assertThat(v).isEqualTo(FrameVec.Parts(Value.Literal(2.0), Value.Literal(0.5), Value.Literal(0.0)))
        // Basis convention: x = left, y = up, z = forward
        assertThat(v.toLocal()).isEqualTo(Value.Literal(Vec3(0.0, 0.5, 2.0)))
    }

    @Test
    fun `param and CEL parts become one compiled vec expression, whole vector may be a param`() {
        val v = decodeVec("{forward: '\${reach}', up: '= reach * 0.5'}")
        val local = v.toLocal() as Value.Expr
        assertThat(local.source).isEqualTo("vec(0.0, (reach * 0.5), reach)")
        assertThat(cel.compile(local.source, ctx.env, ExprType.VEC3)).isInstanceOf(Result.Success::class.java)
        assertThat(decodeVec("'= vec(0, 1, 0)'").toLocal()).isEqualTo(Value.Expr("vec(0, 1, 0)"))
    }

    @Test
    fun `positional triple and unknown axis are rejected with path`() {
        val positional = err(Grammar.guard {
            FrameVecs.type.decode(yaml("[1, 2, 3]"), ctx, "anchors[0].offset")
        })
        assertThat(positional.path).isEqualTo("anchors[0].offset")
        assertThat(positional.message).contains("positional")
        val unknown = err(Grammar.guard {
            FrameVecs.type.decode(yaml("{forwrd: 1}"), ctx, "anchors[0].offset")
        })
        assertThat(unknown).isEqualTo(SkillDecodeError.UnknownKey("couture-rapier-s2.yaml", "anchors[0].offset", "forwrd"))
    }

    // ---------------------------------------------------------------- rotate

    private fun rotation(text: String): Rotation = ok(Grammar.guard {
        Rotations.type.decode(yaml(text), ctx, "anchors[0].turn")
    })

    private fun quat(text: String): Quaternion =
        ((rotation(text).toValue(ctx, "anchors[0].turn")) as Value.Literal).value

    @Test
    fun `rotation is right-handed and composes in list order`() {
        val forward = Vec3(0.0, 0.0, 1.0)
        // +deg about up turns forward toward left (+x) (ADR-0303 section 0)
        val turned = quat("[{axis: up, deg: 90}]").rotate(forward)
        assertThat(turned.x).isCloseTo(1.0, tol); assertThat(turned.z).isCloseTo(0.0, tol)
        // +deg about left tips up toward forward
        val tipped = quat("[{axis: left, deg: 90}]").rotate(Vec3(0.0, 1.0, 0.0))
        assertThat(tipped.z).isCloseTo(1.0, tol)

        // R = R_A * R_B: B acts on the object first, A turns the result
        val composed = quat("[{axis: left, deg: 90}, {axis: up, deg: 90}]")
        val a = Quaternion.axisAngle(Vec3(1.0, 0.0, 0.0), Math.toRadians(90.0))
        val b = Quaternion.axisAngle(Vec3(0.0, 1.0, 0.0), Math.toRadians(90.0))
        assertQuat(composed, a * b)
        val swapped = quat("[{axis: up, deg: 90}, {axis: left, deg: 90}]")
        assertThat(swapped.rotate(forward).y).isNotCloseTo(composed.rotate(forward).y, tol)

        // frame-prefixed and negated axes, and the quaternion literal form
        assertQuat(quat("[{axis: frame.up, deg: 90}]"), b)
        assertQuat(quat("[{axis: -up, deg: 90}]"), Quaternion.axisAngle(Vec3(0.0, -1.0, 0.0), Math.toRadians(90.0)))
        assertQuat(quat("[0, 0, 0, 1]"), Quaternion.IDENTITY)
    }

    @Test
    fun `rotation with a CEL angle compiles to a quaternion expression`() {
        val v = rotation("[{axis: up, deg: '= i * 120'}, {axis: forward, deg: '\${turns}'}]").toValue(ctx, "anchors[0].turn")
        val src = (v as Value.Expr).source
        assertThat(src).isEqualTo("quat_axis_angle(vec(0.0, 1.0, 0.0), (i * 120)) * quat_axis_angle(vec(0.0, 0.0, 1.0), turns)")
        assertThat(cel.compile(src, ctx.env, ExprType.QUAT)).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `unknown axis and item axes without a frame table are load errors`() {
        val bad = err(Grammar.guard {
            Rotations.type.decode(yaml("[{axis: sideways, deg: 5}]"), ctx, "anchors[0].turn")
        })
        assertThat(bad.path).isEqualTo("anchors[0].turn[0].axis")
        val long = err(Grammar.guard {
            rotation("[{axis: long, deg: 5}]").toValue(ctx, "anchors[0].turn")
        })
        assertThat(long.path).isEqualTo("anchors[0].turn[0].axis")
    }

    // ---------------------------------------------------------------- timing + Fields

    private val sound = Fields.of(
        "sound",
        Field.required("id", Types.id, "sound cue id"),
        Field.optional("volume", Types.number, Value.Literal(1.0), "linear volume"),
    ) + TimingSpec.fields

    private fun timing(text: String, kind: TimingKind): Result<Timing, SkillDecodeError> =
        Grammar.guard {
            val v = sound.read(yaml(text), ctx, "presentation.timeline[2]")
            TimingSpec.from(v, ctx, "presentation.timeline[2]", kind)
        }

    @Test
    fun `timing block decodes at for every times until lasts`() {
        val t = ok(timing("{id: tick, at: 'release+2', every: 4, for: cast, times: 5, until: hit}", TimingKind.POINT))
        assertThat(t.at).isEqualTo(TimeAt.Event("release", 2))
        assertThat(t.span).isEqualTo(Span.UntilCastEnds)
        assertThat(t.every).isEqualTo(Every.Fixed(Value.Literal(4.0)))
        assertThat(t.times).isEqualTo(Value.Literal(5.0))
        assertThat(t.until).isEqualTo("hit")

        val ext = ok(timing("{id: tick, at: 8, for: 20, every: {from: 6, to: 2, ease: in_quad}, lasts: '\${reach}'}", TimingKind.EXTENDED))
        assertThat(ext.at).isEqualTo(TimeAt.Tick(Value.Literal(8.0)))
        assertThat(ext.every).isInstanceOf(Every.Eased::class.java)
        assertThat(ext.lasts).isEqualTo(Value.ParamRef("reach"))
        // nothing written = the spec defaults (null), stated in the spec
        assertThat(ok(timing("{id: tick}", TimingKind.POINT))).isEqualTo(Timing(null, null, null, null, null, null))
    }

    @Test
    fun `timing rules are load errors with path and field`() {
        assertThat(err(timing("{id: x, every: 4}", TimingKind.POINT)).path).isEqualTo("presentation.timeline[2].every")
        assertThat(err(timing("{id: x, every: 4, for: 8}", TimingKind.EXTENDED)).path).isEqualTo("presentation.timeline[2].lasts")
        // times without every is a family of N simultaneous instances (Timing.kt field doc), not an error
        assertThat(ok(timing("{id: x, times: 3}", TimingKind.POINT)).times).isNotNull()
        assertThat(err(timing("{id: x, lasts: 3}", TimingKind.POINT)).path).isEqualTo("presentation.timeline[2].lasts")
    }

    @Test
    fun `missing required key and unknown key name file path and field`() {
        val missing = err(sound.decode(yaml("{volume: 0.5}"), ctx, "presentation.timeline[2]"))
        assertThat(missing).isEqualTo(SkillDecodeError.MissingField("couture-rapier-s2.yaml", "presentation.timeline[2]", "id"))
        val unknown = err(sound.decode(yaml("{id: a, volum: 0.5}"), ctx, "presentation.timeline[2]"))
        assertThat(unknown).isEqualTo(SkillDecodeError.UnknownKey("couture-rapier-s2.yaml", "presentation.timeline[2]", "volum"))
        val wrongType = err(sound.decode(yaml("{id: a, volume: loud}"), ctx, "presentation.timeline[2]"))
        assertThat(wrongType).isInstanceOf(SkillDecodeError.TypeMismatch::class.java)
        assertThat(wrongType.path).isEqualTo("presentation.timeline[2].volume")
    }

    @Test
    fun `explicit default is applied and reported as present only when written`() {
        val values = ok(sound.decode(yaml("{id: a}"), ctx, "p"))
        val volume = sound.fields.first { it.name == "volume" }
        assertThat(values.isPresent(volume)).isFalse()
        @Suppress("UNCHECKED_CAST")
        assertThat(values[volume as Field<Value<Double>>]).isEqualTo(Value.Literal(1.0))
    }

    // ---------------------------------------------------------------- params and CEL in numeric fields

    @Test
    fun `undeclared param and bad or mistyped CEL are load errors, declared ones pass`() {
        val one = Fields.of("n", Field.required("n", Types.number))
        assertThat(ok(one.decode(yaml("{n: '\${reach}'}"), ctx, "x"))[one.fields[0] as Field<Value<Double>>]).isEqualTo(Value.ParamRef("reach"))
        assertThat(err(one.decode(yaml("{n: '\${nope}'}"), ctx, "x"))).isInstanceOf(SkillDecodeError.UndefinedReference::class.java)
        assertThat(err(one.decode(yaml("{n: '= reach +'}"), ctx, "x"))).isInstanceOf(SkillDecodeError.BadExpr::class.java)
        assertThat(err(one.decode(yaml("{n: '= vec(1, 2, 3)'}"), ctx, "x"))).isInstanceOf(SkillDecodeError.BadExpr::class.java)
        assertThat(ctx.resolveNumber(Value.ParamRef("turns"))).isEqualTo(3.0)
        assertThat(ctx.resolveNumber(Value.Expr("reach"))).isNull()
    }

    // ---------------------------------------------------------------- schema fragment

    @Test
    fun `the same spec emits its JSON-schema fragment`() {
        val schema = sound.jsonSchema()
        assertThat(schema["type"]).isEqualTo("object")
        assertThat(schema["additionalProperties"]).isEqualTo(false)
        assertThat(schema["required"]).isEqualTo(listOf("id"))
        @Suppress("UNCHECKED_CAST")
        val props = schema["properties"] as Map<String, Map<String, Any?>>
        assertThat(props.keys).containsExactly("id", "volume", "at", "for", "every", "times", "until", "lasts")
        assertThat(props["volume"]!!["default"]).isEqualTo(1.0)
        assertThat(props["until"]!!["default"]).isNull()
        assertThat(props["until"]!!.containsKey("default")).isTrue()

        val small = Fields.of("d", Field.required("a", Types.id), Field.optional("b", Types.enumOf("x", "y"), "x"))
        assertThat(SchemaJson.write(small.jsonSchema())).isEqualTo(
            """{"type":"object","additionalProperties":false,"required":["a"],"properties":{"a":{"type":"string","pattern":"^\\S+$"},"b":{"enum":["x","y"],"x-listed-default":"x"}}}""",
        )
        val vecSchema = SchemaJson.write(FrameVecs.axes.jsonSchema())
        assertThat(vecSchema).contains("\"forward\"", "\"up\"", "\"left\"", "\"required\":[]")
    }
}
