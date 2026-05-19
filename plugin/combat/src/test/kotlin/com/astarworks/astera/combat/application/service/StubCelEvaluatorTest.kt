package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.domain.skill.expression.BindingRef
import com.astarworks.astera.combat.domain.skill.expression.CelContext
import com.astarworks.astera.combat.domain.skill.expression.CelError
import com.astarworks.astera.combat.domain.skill.expression.CelSkillScript
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.attribute.SimpleAttributeContainer
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class StubCelEvaluatorTest {

    private val evaluator = StubCelEvaluator()

    private fun ctx(): CelContext = CelContext(
        player = CelContext.PlayerData(
            playerId = PlayerId(UUID.randomUUID()),
            attr = SimpleAttributeContainer(),
        ),
    )

    @Test
    fun `evaluate returns Failure with NotYetImplemented for trivial source`() {
        val script = CelSkillScript(source = "1")
        val result = evaluator.evaluate(script, ctx())

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.errorOrNull()).isEqualTo(CelError.NotYetImplemented)
    }

    @Test
    fun `evaluate returns Failure with NotYetImplemented for player attr lookup`() {
        val script = CelSkillScript(
            source = "player.attr['stat.max_hp']",
            referencedBindings = listOf(BindingRef("player.attr", "stat.max_hp")),
        )
        val result = evaluator.evaluate(script, ctx())

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.errorOrNull()).isEqualTo(CelError.NotYetImplemented)
    }

    @Test
    fun `evaluate returns Failure with NotYetImplemented when target present`() {
        val script = CelSkillScript(source = "target.attr['stat.kills']")
        val target = CelContext.PlayerData(
            playerId = PlayerId(UUID.randomUUID()),
            attr = SimpleAttributeContainer(),
        )
        val context = ctx().copy(target = target)

        val result = evaluator.evaluate(script, context)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.errorOrNull()).isEqualTo(CelError.NotYetImplemented)
    }

    @Test
    fun `error messageKey resolves to astera combat cel error not yet implemented`() {
        val script = CelSkillScript(source = "anything")
        val result = evaluator.evaluate(script, ctx())

        val error = result.errorOrNull()!!
        assertThat(error.messageKey.value)
            .isEqualTo("astera.combat.cel.error.not_yet_implemented")
    }

    @Test
    fun `NotYetImplemented is a singleton data object`() {
        val a: CelError = CelError.NotYetImplemented
        val b: CelError = CelError.NotYetImplemented
        assertThat(a).isSameAs(b)
    }
}
