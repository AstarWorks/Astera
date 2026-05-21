package com.astarworks.astera.core.domain.anticheat

import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MovementValidationDomainTest {

    @Test
    fun `MovementValidation Ok is a singleton`() {
        assertThat(MovementValidation.Ok).isSameAs(MovementValidation.Ok)
    }

    @Test
    fun `Suspicious carries the underlying suspicion`() {
        val reason = MovementSuspicion.HighSpeed(measured = 1.5, max = 1.0)
        val verdict = MovementValidation.Suspicious(reason)
        assertThat(verdict.reason).isEqualTo(reason)
    }

    @Test
    fun `Reject carries the underlying rejection`() {
        val from = Vec3(0.0, 64.0, 0.0)
        val to = Vec3(100.0, 64.0, 0.0)
        val verdict = MovementValidation.Reject(MovementRejection.Teleport(distance = 100.0))
        val reject = verdict.reason as MovementRejection.Teleport
        assertThat(reject.distance).isEqualTo(100.0)
        // Vec3 referenced to ensure imports + math types resolve.
        assertThat((to - from).length()).isEqualTo(100.0)
    }

    @Test
    fun `NoClip rejection is a singleton`() {
        assertThat(MovementRejection.NoClip).isSameAs(MovementRejection.NoClip)
    }

    @Test
    fun `WallClipping retains pre and post positions`() {
        val from = Vec3(1.0, 64.0, 1.0)
        val to = Vec3(2.0, 64.0, 1.0)
        val susp = MovementSuspicion.WallClipping(from, to)
        assertThat(susp.from).isEqualTo(from)
        assertThat(susp.to).isEqualTo(to)
    }
}
