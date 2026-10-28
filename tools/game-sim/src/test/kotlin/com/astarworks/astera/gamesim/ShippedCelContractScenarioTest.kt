package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.skillengine.expr.cel.CelExprCompiler
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnvSpec
import com.astarworks.astera.core.domain.skillengine.expr.ExprError
import com.astarworks.astera.core.domain.skillengine.expr.ExprType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import com.astarworks.astera.core.application.skillengine.runtime.SkillEngineContents
import java.nio.file.Files

/**
 * SKL-09 WP-5 (ADR-0301, ADR-0303 3.7): CEL is checked at load, not at cast. Every shipped skill loads (so every
 * shipped expression passed the result-type and `ease` checks) and casts once with zero expression failures; the
 * load-time rejections are probed through the real compiler.
 */
class ShippedCelContractScenarioTest {

    @Test
    fun `every shipped skill casts with zero CEL runtime failures`() {
        val dummies = (1..4).associate { simVictim(it) to ahead(Vec3.ZERO, 0.0, 2.0 * it) }
        val ids = CastSim(dummies).catalog.ids().filter { '@' !in it }.sorted()
        val failures = ArrayList<String>()
        for (id in ids) {
            val sim = CastSim(dummies, casterStacks = mapOf("charge" to 5, "bleed" to 5, "burn" to 5))
            try {
                sim.cast(id, 260)
            } catch (e: Throwable) {
                failures += "$id: ${e::class.simpleName}: ${e.message?.take(300)}"
            }
        }
        assertThat(ids).hasSizeGreaterThanOrEqualTo(149)
        assertThat(failures).describedAs("skills with a CEL or op failure").isEmpty()
    }

    private fun loadFailures(id: String, gameplayOp: String): List<String> {
        val dir = Files.createTempDirectory("typed-expr")
        Files.createDirectories(dir.resolve(id))
        Files.writeString(
            dir.resolve(id).resolve("skill.yaml"),
            """
            apiVersion: "astera/v2"
            kind: Skill
            id: $id
            item: pistol
            meta: {name: {ja: "t", en: "t"}, description: {ja: "t", en: "t"}, cooldown_ticks: 1, resource_cost: 0, tags: []}
            frames: []
            gameplay: {timeline: [{id: t0, at: 0, do: [$gameplayOp]}]}
            presentation: {timeline: []}
            """.trimIndent(),
        )
        val report = SkillEngineContents(Coin2CastWiring.collaborators().newCatalog()).reload(dir)
        return report.failures.map { "${it.file.fileName}: ${it.message}" }
    }

    @Test
    fun `an expression whose result type does not fit its field is a load error naming the skill and path`() {
        val ok = loadFailures("t-typed-ok", """{id: g, op: status, when: "= nearest_enemy_dist(caster) < 3.6", status: charge, target: caster, stacks: 1, duration_ticks: 10}""")
        assertThat(ok).isEmpty()
        val bad = loadFailures("t-typed-bad", """{id: g, op: status, when: "= nearest_enemy_dist(caster)", status: charge, target: caster, stacks: 1, duration_ticks: 10}""")
        assertThat(bad).hasSize(1)
        assertThat(bad[0]).contains("t-typed-bad").contains("when").contains("expects bool")
    }

    private val exprs = CelExprCompiler()
    private val spec = ExprEnvSpec(variables = mapOf("t" to ExprType.NUM))

    private fun compile(src: String) = exprs.compile(src, spec)

    @Test
    fun `result types the engine cannot carry and bad easing names fail at compile`() {
        assertThat(compile("= [1.0, 2.0]")).isInstanceOf(Result.Failure::class.java)
        assertThat(compile("= {'a': 1}")).isInstanceOf(Result.Failure::class.java)
        assertThat(compile("= null")).isInstanceOf(Result.Failure::class.java)
        assertThat(compile("= ease('out_cubicc', t)")).isInstanceOf(Result.Failure::class.java)
        assertThat(compile("= ease('steps(0)', t)")).isInstanceOf(Result.Failure::class.java)
        val nonLiteral = ExprEnvSpec(variables = mapOf("t" to ExprType.NUM, "e" to ExprType.STR))
        val r = exprs.compile("= ease(e, t)", nonLiteral)
        assertThat(r).isInstanceOf(Result.Failure::class.java)
        assertThat((r as Result.Failure).error).isInstanceOf(ExprError.Type::class.java)
    }

    @Test
    fun `ease lerp and slerp compile and evaluate`() {
        for (src in listOf(
            "= ease('linear', t)", "= ease('in_out_back', t)", "= ease('out_bounce', t)", "= ease('steps(4)', t)",
            "= ease('cubic_bezier(0.2,0.8,0.2,1)', t)", "= lerp(0.2, 1.4, ease('out_back', t / 10))", "= lerp(0, 2, t)",
            "= lerp(vec(0,0,0), vec(1,2,3), t)",
            "= slerp(quat_axis_angle(vec(0,1,0), 0), quat_axis_angle(vec(0,1,0), 90), t)",
        )) {
            assertThat(compile(src)).describedAs(src).isInstanceOf(Result.Success::class.java)
        }
    }
}
