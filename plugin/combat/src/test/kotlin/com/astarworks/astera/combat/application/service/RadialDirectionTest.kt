package com.astarworks.astera.combat.application.service

import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RadialDirectionTest {

    private val caster = Vec3(1.0, 64.0, 1.0)

    @Test
    fun `away is horizontal and ignores height difference`() {
        val d = RadialDirection.compute(caster, Vec3(1.0, 70.0, 6.0), null, 0.0, toward = false)
        assertThat(d).isEqualTo(Vec3(0.0, 0.0, 1.0))
    }

    @Test
    fun `vertical is added after normalising and toward reverses the horizontal part`() {
        val d = RadialDirection.compute(caster, Vec3(11.0, 64.0, 1.0), null, 0.4, toward = true)
        assertThat(d).isEqualTo(Vec3(-1.0, 0.4, 0.0))
    }

    @Test
    fun `a target on the caster falls back to the horizontal look, then +X`() {
        assertThat(RadialDirection.compute(caster, caster, Vec3(0.0, 0.7, 2.0), 0.0, false)).isEqualTo(Vec3(0.0, 0.0, 1.0))
        assertThat(RadialDirection.compute(caster, caster, null, 0.0, false)).isEqualTo(Vec3(1.0, 0.0, 0.0))
    }
}
