package com.astarworks.astera.core.application.service.anticheat

import com.astarworks.astera.core.domain.anticheat.DamageRateValidation
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class SlidingWindowDamageRateValidatorTest {

    private val attacker = PlayerId(UUID.randomUUID())
    private val victim = PlayerId(UUID.randomUUID())

    @Test
    fun `no prior damage returns Ok`() {
        val validator = SlidingWindowDamageRateValidator()
        val verdict = validator.validate(attacker, victim, atTick = 0)
        assertThat(verdict).isEqualTo(DamageRateValidation.Ok)
    }

    @Test
    fun `damage burst beyond cap returns TooFast`() {
        val validator = SlidingWindowDamageRateValidator(
            windowTicks = 20, // 1s
            capPerSecond = 4.0,
        )
        repeat(10) { i -> validator.record(attacker, atTick = i.toLong()) }
        val verdict = validator.validate(attacker, victim, atTick = 10)
        assertThat(verdict).isInstanceOf(DamageRateValidation.TooFast::class.java)
        val tf = verdict as DamageRateValidation.TooFast
        assertThat(tf.cap).isEqualTo(4.0)
        assertThat(tf.measuredDps).isGreaterThan(tf.cap)
    }

    @Test
    fun `window evicts old samples`() {
        val validator = SlidingWindowDamageRateValidator(
            windowTicks = 20,
            capPerSecond = 4.0,
        )
        repeat(10) { i -> validator.record(attacker, atTick = i.toLong()) }
        // Sample much later — outside window.
        val verdict = validator.validate(attacker, victim, atTick = 200)
        assertThat(verdict).isEqualTo(DamageRateValidation.Ok)
    }

    @Test
    fun `reset clears tracking`() {
        val validator = SlidingWindowDamageRateValidator(capPerSecond = 1.0)
        repeat(20) { i -> validator.record(attacker, atTick = i.toLong()) }
        validator.reset(attacker)
        assertThat(validator.validate(attacker, victim, atTick = 20))
            .isEqualTo(DamageRateValidation.Ok)
    }
}
