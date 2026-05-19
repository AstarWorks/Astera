package com.astarworks.astera.core.domain.effect

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Unit tests for the [ParticleDefinition] domain value class (Wave 5f).
 *
 * Pure-domain test — exercises only the `init` invariants on
 * [ParticleDefinition] / [ParticleColor] / [ParticleId] / [ParticleShape].
 * No I/O, no Bukkit, no fakes.
 */
class ParticleDefinitionTest {

    private fun anyShape(): ParticleShape = ParticleShape.Point
    private fun anyColor(): ParticleColor = ParticleColor(0, 0, 0)

    @Test
    fun `count must be positive`() {
        assertThatThrownBy {
            ParticleDefinition(
                id = ParticleId("p"),
                shape = anyShape(),
                color = anyColor(),
                count = 0,
                lifetimeTicks = 1L,
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("count must be positive")
    }

    @Test
    fun `count of one is accepted`() {
        val def = ParticleDefinition(
            id = ParticleId("p"),
            shape = anyShape(),
            color = anyColor(),
            count = 1,
            lifetimeTicks = 1L,
        )
        assertThat(def.count).isEqualTo(1)
    }

    @Test
    fun `lifetimeTicks must be positive`() {
        assertThatThrownBy {
            ParticleDefinition(
                id = ParticleId("p"),
                shape = anyShape(),
                color = anyColor(),
                count = 1,
                lifetimeTicks = 0L,
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("lifetimeTicks must be positive")
    }

    @Test
    fun `RGB components above 255 are rejected`() {
        assertThatThrownBy { ParticleColor(256, 0, 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("RGB components must be 0..255")
        assertThatThrownBy { ParticleColor(0, 256, 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ParticleColor(0, 0, 256) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `RGB components below zero are rejected`() {
        assertThatThrownBy { ParticleColor(-1, 0, 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ParticleColor(0, -1, 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ParticleColor(0, 0, -1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `RGB boundary values 0 and 255 are accepted`() {
        val a = ParticleColor(0, 0, 0)
        val b = ParticleColor(255, 255, 255)
        assertThat(a.r).isZero()
        assertThat(b.r).isEqualTo(255)
    }

    @Test
    fun `ParticleId must be lower-kebab`() {
        assertThatThrownBy { ParticleId("Example") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("ParticleId must be lower-kebab")
        assertThatThrownBy { ParticleId("with_underscore") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ParticleId("with.dot") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ParticleId("1starts-with-digit") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ParticleId("") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `ParticleId accepts canonical lower-kebab tokens`() {
        assertThat(ParticleId("burst").value).isEqualTo("burst")
        assertThat(ParticleId("example-burst").value).isEqualTo("example-burst")
        assertThat(ParticleId("a1-b2-c3").value).isEqualTo("a1-b2-c3")
    }

    @Test
    fun `ParticleShape dimensions must be positive`() {
        assertThatThrownBy { ParticleShape.Line(0.0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ParticleShape.Circle(-1.0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ParticleShape.Sphere(0.0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ParticleShape.Burst(-0.1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `ParticleShape Point is a stable singleton`() {
        assertThat(ParticleShape.Point).isSameAs(ParticleShape.Point)
    }
}
