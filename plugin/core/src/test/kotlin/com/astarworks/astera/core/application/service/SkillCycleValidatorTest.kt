package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.condition.Condition
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
import com.astarworks.astera.core.domain.skill.phase.PhaseMechanics
import com.astarworks.astera.core.domain.skill.targeter.Targeter
import com.astarworks.astera.core.domain.skill.value.IntValue
import com.astarworks.astera.core.domain.skill.value.NumericValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SkillCycleValidatorTest {

    private val validator = SkillCycleValidator()

    private fun cast(toId: String): Mechanic.CastSkill =
        Mechanic.CastSkill(targets = Targeter.Caster, skillId = SkillId(toId))

    private fun aWith(vararg mechs: Mechanic): PhaseMechanics =
        PhaseMechanics.Aoe(cast = mechs.toList())

    @Test
    fun `empty graph is acyclic`() {
        val result = validator.validate(emptyMap())
        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `linear DAG A to B to C is acyclic`() {
        val graph = mapOf(
            SkillId("a") to aWith(cast("b")),
            SkillId("b") to aWith(cast("c")),
            SkillId("c") to PhaseMechanics.Aoe(),
        )
        val result = validator.validate(graph)
        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `branching DAG is acyclic`() {
        val graph = mapOf(
            SkillId("root") to aWith(cast("left"), cast("right")),
            SkillId("left") to aWith(cast("leaf")),
            SkillId("right") to aWith(cast("leaf")),
            SkillId("leaf") to PhaseMechanics.Aoe(),
        )
        val result = validator.validate(graph)
        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `self-loop is detected as cycle`() {
        val graph = mapOf(
            SkillId("loop") to aWith(cast("loop")),
        )
        val result = validator.validate(graph)
        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val err = (result as Result.Failure).error
        assertThat(err.cycles).hasSize(1)
        assertThat(err.cycles[0]).containsExactly(SkillId("loop"))
    }

    @Test
    fun `two-cycle A to B to A is detected`() {
        val graph = mapOf(
            SkillId("a") to aWith(cast("b")),
            SkillId("b") to aWith(cast("a")),
        )
        val result = validator.validate(graph)
        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val err = (result as Result.Failure).error
        assertThat(err.cycles).hasSize(1)
        assertThat(err.cycles[0]).containsExactlyInAnyOrder(SkillId("a"), SkillId("b"))
    }

    @Test
    fun `three-cycle A to B to C to A is detected`() {
        val graph = mapOf(
            SkillId("a") to aWith(cast("b")),
            SkillId("b") to aWith(cast("c")),
            SkillId("c") to aWith(cast("a")),
        )
        val result = validator.validate(graph)
        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val err = (result as Result.Failure).error
        assertThat(err.cycles).hasSize(1)
        assertThat(err.cycles[0]).containsExactlyInAnyOrder(
            SkillId("a"), SkillId("b"), SkillId("c"),
        )
    }

    @Test
    fun `nested CastSkill inside IfThen is followed`() {
        val nestedCycle = Mechanic.IfThen(
            targets = Targeter.Caster,
            predicate = Condition.HealthBelow(Targeter.Caster, NumericValue.Literal(0.5)),
            then_ = listOf(cast("a")),
        )
        val graph = mapOf(
            SkillId("a") to PhaseMechanics.Aoe(cast = listOf(cast("b"))),
            SkillId("b") to PhaseMechanics.Aoe(cast = listOf(nestedCycle)),
        )
        val result = validator.validate(graph)
        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val err = (result as Result.Failure).error
        assertThat(err.cycles).hasSize(1)
        assertThat(err.cycles[0]).containsExactlyInAnyOrder(SkillId("a"), SkillId("b"))
    }

    @Test
    fun `nested CastSkill inside Loop is followed`() {
        val loop = Mechanic.Loop(
            targets = Targeter.Caster,
            count = IntValue.Literal(3),
            mechanics = listOf(cast("a")),
        )
        val graph = mapOf(
            SkillId("a") to PhaseMechanics.Aoe(cast = listOf(loop)),
        )
        val result = validator.validate(graph)
        assertThat(result).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `nested CastSkill inside Choose is followed`() {
        val choose = Mechanic.Choose(
            targets = Targeter.Caster,
            weighted = listOf(
                Mechanic.WeightedBranch(1.0, listOf(cast("a"))),
                Mechanic.WeightedBranch(1.0, listOf(cast("b"))),
            ),
        )
        val graph = mapOf(
            SkillId("a") to PhaseMechanics.Aoe(cast = listOf(choose)),
            SkillId("b") to PhaseMechanics.Aoe(),
        )
        val result = validator.validate(graph)
        // a -> a via choose branch creates a self loop on a.
        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val err = (result as Result.Failure).error
        // The cycle includes a (self-loop). b is not in any cycle.
        assertThat(err.cycles.flatten()).contains(SkillId("a"))
    }

    @Test
    fun `unknown skill reference does not crash`() {
        val graph = mapOf(
            SkillId("a") to aWith(cast("does-not-exist")),
        )
        val result = validator.validate(graph)
        assertThat(result).isInstanceOf(Result.Success::class.java)
    }
}
