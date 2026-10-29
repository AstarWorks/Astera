package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.load.grammar.AnchorRef
import com.astarworks.astera.core.application.skillengine.load.ops.presentation.ParticleDefOpSpec
import com.astarworks.astera.core.application.skillengine.load.ops.presentation.ParticleOp
import com.astarworks.astera.core.application.skillengine.load.ops.presentation.ParticleOpSpec
import com.astarworks.astera.core.application.skillengine.load.ops.presentation.ParticleStyle
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.application.skillengine.expr.cel.CelExprCompiler
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnv
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnvSpec
import com.astarworks.astera.core.domain.skillengine.expr.ExprValue
import com.astarworks.astera.core.domain.skillengine.expr.FrameLookup
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.Val
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * W1-04 acceptance at the decode layer (ADR-0303 2.2, 2.0b). Lowering to rows (A1 examples d, i, j) runs through the
 * W1-20 harness (loadInline / play) once it lands; until then these cases pin the field table and its validation.
 */
class ParticleOpScenarioTest : SkillEngineScenario() {
    private val ctx = GrammarContext("particle-op-test.yaml")
    private val path = "machine.states.cast.do[0]"

    private fun decodeParticle(raw: Map<String, Any?>): Result<ParticleOp, SkillDecodeError> =
        when (val r = ParticleOpSpec.fields.decode(raw, ctx, path)) {
            is Result.Success -> ParticleOpSpec.decode(OpId("puff"), SrcPath("test"), null, null, r.value, ctx)
            is Result.Failure -> Result.Failure(r.error)
        }

    @Test
    fun dustWithColorAndSizeDecodesWithVanillaDefaults() {
        val r = decodeParticle(mapOf("particle" to "dust", "anchor" to "palm", "color" to "#ff5fa2", "size" to 1.2))
        val op = (r as Result.Success).value
        assertEquals(Value.Literal(1.0), op.count)
        assertEquals(Value.Literal(0.0), op.extra)
        assertEquals(Value.Literal(com.astarworks.astera.core.domain.geometry.Vec3(0.0, 0.0, 0.0)), op.offset)
        val dust = assertInstanceOf(ParticleStyle.Dust::class.java, op.style)
        assertEquals(255, dust.color.r)
        assertEquals(95, dust.color.g)
        assertEquals(162, dust.color.b)
        assertEquals(1.2, dust.size, 1e-9)
        assertEquals(AnchorRef("palm", null, null), op.anchor)
    }

    @Test
    fun sizeWithoutColorIsRejected() {
        assertTrue(decodeParticle(mapOf("particle" to "dust", "anchor" to "palm", "size" to 1.2)) is Result.Failure)
    }

    @Test
    fun colorWithoutSizeIsRejected() {
        assertTrue(decodeParticle(mapOf("particle" to "dust", "anchor" to "palm", "color" to "#ff5fa2")) is Result.Failure)
    }

    @Test
    fun blockAndColorTogetherAreRejected() {
        val r = decodeParticle(
            mapOf("particle" to "dust", "anchor" to "palm", "block" to "stone", "color" to "#ff5fa2", "size" to 1.0),
        )
        assertTrue(r is Result.Failure)
    }

    @Test
    fun deletedV2FieldsAreRejected() {
        // dir / spread_deg / speed / inherit_velocity / seed / vanilla were deleted (ADR-0303 2.2); no silent drop.
        listOf("dir", "spread_deg", "speed", "inherit_velocity", "seed", "vanilla").forEach { key ->
            val r = decodeParticle(mapOf("particle" to "crit", "anchor" to "muzzle", key to 1))
            assertTrue(r is Result.Failure, "field '$key' must be rejected")
        }
    }

    @Test
    fun fractionalCountIsRejected() {
        assertTrue(decodeParticle(mapOf("particle" to "flame", "anchor" to "muzzle", "count" to 2.5)) is Result.Failure)
    }

    @Test
    fun anchorSweepMapDecodes() {
        val r = decodeParticle(
            mapOf("particle" to "end_rod", "anchor" to mapOf("of" to "bolt", "sweep" to 0.3)),
        )
        val op = (r as Result.Success).value
        assertEquals(AnchorRef("bolt", null, 0.3), op.anchor)
    }

    @Test
    fun particleDefDecodesAsPointKind() {
        val raw = mapOf("def" to "frost-cast", "anchor" to "muzzle")
        val values = (ParticleDefOpSpec.fields.decode(raw, ctx, path) as Result.Success).value
        val op = (ParticleDefOpSpec.decode(OpId("frost"), SrcPath("test"), null, null, values, ctx) as Result.Success).value
        assertEquals("frost-cast", op.def)
        assertEquals(AnchorRef("muzzle", null, null), op.anchor)
        assertEquals(com.astarworks.astera.core.application.skillengine.load.grammar.TimingKind.POINT, ParticleDefOpSpec.timing)
    }

    // ---- W2-04: shift / times families / R28 per-index lists / R11 lint (lowered rows, Live sources evaluated here) ----

    private fun skill(vararg ops: String, timeline: String = "at: 0"): String = """
        apiVersion: astera/v3
        kind: Skill
        id: particle-probe
        anchors:
          - {id: body, anchor: caster}
        machine:
          start: s
          states:
            s:
              for: 20
              do:
                - id: fx0
                  $timeline
                  do:
        """.trimIndent() + "\n" + ops.joinToString("\n") { "                    - $it" }

    private fun loaded(yaml: String): CompiledSkill {
        val r = loadInline(yaml)
        assertTrue(r is Result.Success, "expected a load, got $r")
        return (r as Result.Success).value
    }

    private fun particles(s: CompiledSkill): List<Action.Particle> =
        s.table.states.flatMap { it.rows }.map { it.action }.filterIsInstance<Action.Particle>()

    private val origin = object : FrameLookup {
        override fun worldPose(frame: String, point: String?): Frame? =
            // the anchors-list alias `body` lowers to the caster frame it names
            if (frame == "caster") Frame(Vec3(10.0, 0.0, 0.0), Quaternion(0.0, 0.0, 0.0, 1.0)) else null

        override fun velocity(frame: String, point: String?): Vec3 = Vec3.ZERO
    }
    private val spec = ExprEnvSpec(frames = setOf("caster"))
    private val compiler = CelExprCompiler()

    private fun source(v: Val<*>): String = (v as Val.Live<*>).name

    private fun position(a: Action.Particle, seed: Long = 7L, sample: Int = 0): Vec3 {
        val compiled = when (val c = compiler.compile(source(a.position), spec)) {
            is Result.Success -> c.value
            is Result.Failure -> throw AssertionError("position '${source(a.position)}' did not compile: ${c.error}")
        }
        return (compiled.eval(ExprEnv(emptyMap(), origin, seed, null, seed, sample)) as ExprValue.Vec3).value
    }

    @Test
    fun shiftPerCopyUsesI() {
        val ps = particles(loaded(skill("{id: ring, op: particle, particle: flame, anchor: body, times: 4, shift: {forward: \"= i * 1.0\", up: 0.5}}")))
        assertEquals(4, ps.size)
        val ys = ps.map { position(it).y }
        assertTrue(ys.all { Math.abs(it - 0.5) < 1e-9 }, "up shift carried: $ys")
        val sources = ps.map { source(it.position) }
        assertEquals(4, sources.toSet().size, "each copy bakes its own i: $sources")
    }

    @Test
    fun sameTickTimesWithoutEvery() {
        val ps = particles(loaded(skill("{id: ring, op: particle, particle: flame, anchor: body, times: 5, shift: {left: \"= i\"}}")))
        assertEquals(5, ps.size)
        val rows = loaded(skill("{id: ring, op: particle, particle: flame, anchor: body, times: 5, shift: {left: \"= i\"}}")).table.states.flatMap { it.rows }
        assertEquals(1, rows.map { it.tick }.toSet().size, "a times family without every is simultaneous")
    }

    @Test
    fun nAndIAreBoundInShiftAndPiIsAConstant() {
        val ps = particles(loaded(skill("{id: ring, op: particle, particle: flame, anchor: body, times: 4, shift: {forward: \"= cos(2.0 * PI * i / n)\", left: \"= sin(2.0 * PI * i / n)\"}}")))
        val xs = ps.map { position(it).x - 10.0 }.sorted()
        assertEquals(listOf(-1.0, 0.0, 0.0, 1.0).size, xs.size)
        assertEquals(1.0, xs.last(), 1e-9)
        assertEquals(-1.0, xs.first(), 1e-9)
    }

    @Test
    fun ringPhaseDrawConstantPerCast() {
        // shift `forward` is the +z axis of an identity frame, so the draw is read from z
        val ps = particles(loaded(skill("{id: ring, op: particle, particle: flame, anchor: body, times: 3, shift: {forward: \"= draw('ring_ph')\"}}")))
        val xs = ps.map { position(it).z }
        assertEquals(1, xs.map { Math.round(it * 1e9) }.toSet().size, "draw is one value for every copy of one cast: $xs")
        val later = ps.map { position(it, sample = 9).z }
        assertEquals(xs, later, "draw is constant across ticks of one cast")
        assertTrue(position(ps[0], seed = 7L).z != position(ps[0], seed = 8L).z, "draw differs between casts")
    }

    @Test
    fun jaggedLineRollPerPoint() {
        val ps = particles(loaded(skill("{id: line, op: particle, particle: end_rod, anchor: body, times: 8, shift: \"= roll_unit('jit') * 0.2\"}")))
        val vs = ps.map { position(it) }
        assertEquals(8, vs.map { Triple(Math.round(it.x * 1e9), Math.round(it.y * 1e9), Math.round(it.z * 1e9)) }.toSet().size, "roll differs per copy i: $vs")
        assertEquals(vs, ps.map { position(it) }, "deterministic for one seed")
    }

    @Test
    fun randInTimingIsLoadError() {
        val r = loadInline(skill("{id: p, op: particle, particle: flame, anchor: body}", timeline = "at: \"= rand() * 10.0\""))
        assertTrue(r is Result.Failure, "rand in a timing field must not load")
        val r2 = loadInline(skill("{id: p, op: particle, particle: flame, anchor: body}", timeline = "at: \"= roll('d') * 10.0\""))
        assertTrue(r2 is Result.Failure, "roll in a timing field must not load")
    }

    @Test
    fun indexedListExpandsPerI() {
        val ps = particles(loaded(skill("{id: p, op: particle, particle: flame, anchor: body, times: 3, count: [1, 2, 3]}")))
        assertEquals(3, ps.size)
        // literal counts lower to Known cells (not Live sources): each copy carries its own entry of the list
        assertEquals(listOf(1.0, 2.0, 3.0), ps.map { (it.count as Val.Known<*>).value.let { v -> (v as Number).toDouble() } })
        assertTrue(ps.all { it.count is Val.Live<*> || it.count is Val.Known<*> })
    }

    @Test
    fun indexedListWrongLengthIsLoadError() {
        val r = loadInline(skill("{id: p, op: particle, particle: flame, anchor: body, times: 3, count: [1, 2]}"))
        assertTrue(r is Result.Failure, "a per-index list needs exactly times entries")
    }

    @Test
    fun anchorOffsetAppliesInAnchorAxes() {
        val ps = particles(loaded(skill("{id: p, op: particle, particle: flame, anchor: {of: body, offset: {up: 2.0}}}")))
        assertEquals(2.0, position(ps.single()).y, 1e-9)
    }

    @Test
    fun particleDefPlaysAsABurstOfItsDef() {
        // W2-04: Action.ParticleDef reaches the particle gateway as one burst whose type is the def id
        val s = loaded(skill("{id: pd, op: particle_def, def: frost-cast, anchor: body}"))
        val calls = play(s, 3L).filter { it.verb == "particle" }
        assertEquals(1, calls.size)
        assertEquals("type=frost-cast", calls.single().payload)
        assertEquals(0, calls.single().tick)
    }
}
