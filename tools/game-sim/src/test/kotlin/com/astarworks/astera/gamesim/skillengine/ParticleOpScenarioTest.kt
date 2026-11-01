package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.load.ops.presentation.ParticleOp
import com.astarworks.astera.core.application.skillengine.load.ops.presentation.ParticleOpSpec
import com.astarworks.astera.core.domain.Result
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
    fun sizeWithoutColorIsRejected() {
        assertTrue(decodeParticle(mapOf("particle" to "dust", "anchor" to "palm", "size" to 1.2)) is Result.Failure)
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

    // ---- W2-04: shift / times families / R28 per-index lists / R11 lint (lowered rows, Live sources evaluated here) ----

    private fun skill(vararg ops: String, timeline: String = "at: 0"): String = """
        apiVersion: astera/v3
        kind: Skill
        id: particle-probe
        anchors:
          - {id: body, anchor: caster, mode: follow}
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
    fun sameTickTimesWithoutEvery() {
        val ps = particles(loaded(skill("{id: ring, op: particle, particle: flame, anchor: body, times: 5, shift: {left: \"= i\"}}")))
        assertEquals(5, ps.size)
        val rows = loaded(skill("{id: ring, op: particle, particle: flame, anchor: body, times: 5, shift: {left: \"= i\"}}")).table.states.flatMap { it.rows }
        assertEquals(1, rows.map { it.tick }.toSet().size, "a times family without every is simultaneous")
    }

    @Test
    fun randInTimingIsLoadError() {
        val r = loadInline(skill("{id: p, op: particle, particle: flame, anchor: body}", timeline = "at: \"= rand() * 10.0\""))
        assertTrue(r is Result.Failure, "rand in a timing field must not load")
        val r2 = loadInline(skill("{id: p, op: particle, particle: flame, anchor: body}", timeline = "at: \"= roll('d') * 10.0\""))
        assertTrue(r2 is Result.Failure, "roll in a timing field must not load")
    }

    @Test
    fun indexedListWrongLengthIsLoadError() {
        val r = loadInline(skill("{id: p, op: particle, particle: flame, anchor: body, times: 3, count: [1, 2]}"))
        assertTrue(r is Result.Failure, "a per-index list needs exactly times entries")
    }

}
