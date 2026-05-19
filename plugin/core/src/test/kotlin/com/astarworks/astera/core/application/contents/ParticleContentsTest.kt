package com.astarworks.astera.core.application.contents

import com.astarworks.astera.core.domain.effect.ParticleColor
import com.astarworks.astera.core.domain.effect.ParticleDefinition
import com.astarworks.astera.core.domain.effect.ParticleId
import com.astarworks.astera.core.domain.effect.ParticleShape
import com.astarworks.astera.core.domain.i18n.MessageKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reload-semantics tests for [ParticleContents] (Wave 5f).
 *
 * Modeled on the older `WeaponContentsTest` — the substrate is shared
 * (`YamlDynamicContents`) so the three reload guarantees we care about are:
 *
 *   1. atomic swap on a clean reload
 *   2. abort on any per-file failure (previous state preserved)
 *   3. programmatic [ParticleContents.register] works alongside bootstrap
 *
 * Bootstrap coverage is light here because the substrate is already covered
 * in `WeaponContentsTest` — these tests focus on the Wave 5f-specific glue
 * (YAML → DTO → domain `ParticleDefinition` round-trip).
 */
class ParticleContentsTest {

    private val burstYaml = """
        id: example-burst
        shape:
          kind: burst
          spread_blocks: 1.5
        color:
          r: 200
          g: 80
          b: 30
        count: 24
        lifetime_ticks: 20
        display_name_key: astera.particle.example-burst.name
    """.trimIndent()

    private val ringYaml = """
        id: example-ring
        shape:
          kind: circle
          radius_blocks: 2.0
        color:
          r: 80
          g: 180
          b: 220
        count: 36
        lifetime_ticks: 40
    """.trimIndent()

    @Test
    fun `bootstrap loads a valid particle and exposes it via find`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("example-burst.yaml"), burstYaml)

        val contents = ParticleContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(0)

        val def = contents.find(ParticleId("example-burst"))
        assertThat(def).isNotNull
        requireNotNull(def)
        assertThat(def.shape).isEqualTo(ParticleShape.Burst(1.5))
        assertThat(def.color).isEqualTo(ParticleColor(200, 80, 30))
        assertThat(def.count).isEqualTo(24)
        assertThat(def.lifetimeTicks).isEqualTo(20L)
        assertThat(def.displayNameKey).isEqualTo(MessageKey("astera.particle.example-burst.name"))
    }

    // ----- Reload semantics ---------------------------------------------

    @Test
    fun `reload swaps to new map when all files parse`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("example-burst.yaml"), burstYaml)

        val contents = ParticleContents()
        contents.bootstrap(dir)
        assertThat(contents.all()).hasSize(1)

        // Replace the single particle with a different one
        Files.delete(dir.resolve("example-burst.yaml"))
        Files.writeString(dir.resolve("example-ring.yaml"), ringYaml)

        val report = contents.reload(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(0)
        assertThat(contents.find(ParticleId("example-burst"))).isNull()
        val ring = contents.find(ParticleId("example-ring"))
        assertThat(ring).isNotNull
        requireNotNull(ring)
        assertThat(ring.shape).isEqualTo(ParticleShape.Circle(2.0))
        assertThat(contents.all()).hasSize(1)
    }

    @Test
    fun `reload is all-or-nothing - one bad file keeps old state intact`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("example-burst.yaml"), burstYaml)

        val contents = ParticleContents()
        contents.bootstrap(dir)
        assertThat(contents.find(ParticleId("example-burst"))).isNotNull

        Files.writeString(dir.resolve("broken.yaml"), "id: : : not valid : yaml :::\n  - oops")

        val report = contents.reload(dir)

        // failed > 0 means reload aborts; old state preserved.
        assertThat(report.failed).isGreaterThan(0)
        assertThat(contents.find(ParticleId("example-burst"))).isNotNull
        assertThat(contents.all()).hasSize(1)
    }

    @Test
    fun `register adds an entry without going through YAML`() {
        val contents = ParticleContents()
        val def = ParticleDefinition(
            id = ParticleId("programmatic"),
            shape = ParticleShape.Point,
            color = ParticleColor(10, 20, 30),
            count = 4,
            lifetimeTicks = 5L,
        )

        contents.register(def)

        assertThat(contents.find(ParticleId("programmatic"))).isEqualTo(def)
    }
}
