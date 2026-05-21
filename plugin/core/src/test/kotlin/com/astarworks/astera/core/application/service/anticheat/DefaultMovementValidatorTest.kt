package com.astarworks.astera.core.application.service.anticheat

import com.astarworks.astera.core.domain.anticheat.MovementRejection
import com.astarworks.astera.core.domain.anticheat.MovementSuspicion
import com.astarworks.astera.core.domain.anticheat.MovementValidation
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class DefaultMovementValidatorTest {

    private val playerId = PlayerId(UUID.randomUUID())
    private val validator = DefaultMovementValidator()

    @Test
    fun `walking one block stays Ok`() {
        val from = Vec3(0.0, 64.0, 0.0)
        val to = Vec3(0.2, 64.0, 0.0)
        val verdict = validator.validate(playerId, from, to, deltaTicks = 1)
        assertThat(verdict).isEqualTo(MovementValidation.Ok)
    }

    @Test
    fun `2 BPS horizontal flags HighSpeed`() {
        val from = Vec3(0.0, 64.0, 0.0)
        val to = Vec3(2.0, 64.0, 0.0)
        val verdict = validator.validate(playerId, from, to, deltaTicks = 1)
        assertThat(verdict).isInstanceOf(MovementValidation.Suspicious::class.java)
        val susp = (verdict as MovementValidation.Suspicious).reason
        assertThat(susp).isInstanceOf(MovementSuspicion.HighSpeed::class.java)
    }

    @Test
    fun `teleport distance triggers Reject`() {
        val from = Vec3(0.0, 64.0, 0.0)
        val to = Vec3(50.0, 64.0, 0.0)
        val verdict = validator.validate(playerId, from, to, deltaTicks = 1)
        assertThat(verdict).isInstanceOf(MovementValidation.Reject::class.java)
        val rej = (verdict as MovementValidation.Reject).reason
        assertThat(rej).isInstanceOf(MovementRejection.Teleport::class.java)
    }

    @Test
    fun `vertical anomaly flags VerticalAnomaly`() {
        val from = Vec3(0.0, 64.0, 0.0)
        val to = Vec3(0.0, 69.0, 0.0)
        val verdict = validator.validate(playerId, from, to, deltaTicks = 1)
        assertThat(verdict).isInstanceOf(MovementValidation.Suspicious::class.java)
        val susp = (verdict as MovementValidation.Suspicious).reason
        assertThat(susp).isInstanceOf(MovementSuspicion.VerticalAnomaly::class.java)
    }

    @Test
    fun `deltaTicks divides the distance`() {
        val from = Vec3(0.0, 64.0, 0.0)
        val to = Vec3(3.0, 64.0, 0.0)
        // 3 blocks over 5 ticks = 0.6 per tick — under HighSpeed cap.
        val verdict = validator.validate(playerId, from, to, deltaTicks = 5)
        assertThat(verdict).isEqualTo(MovementValidation.Ok)
    }
}
