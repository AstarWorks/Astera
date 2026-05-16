package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.domain.status.StatusEffectId
import com.astarworks.astera.combat.domain.status.StatusKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * End-to-end test for [StatusEffectLoaderService] (Stage 1 J).
 *
 * Covers: BURN / SPEED_UP / PARALYSIS happy paths, kind-params mismatch
 * rejection, and the disable variant carrying no payload.
 */
class StatusEffectLoaderServiceTest {

    private val burnYaml = """
        id: test-burn
        display_name_key: astera.status.test-burn.name
        kind: burn
        default_duration_ticks: 60
        stackable: true
        max_stacks: 3
        params:
          type: burn
          damage_per_tick: 1.5
    """.trimIndent()

    private val speedYaml = """
        id: test-speed
        display_name_key: astera.status.test-speed.name
        kind: speed_up
        default_duration_ticks: 100
        params:
          type: speed
          multiplier: 1.4
    """.trimIndent()

    private val paralysisYaml = """
        id: test-paralysis
        display_name_key: astera.status.test-paralysis.name
        kind: paralysis
        default_duration_ticks: 40
        params:
          type: disable
    """.trimIndent()

    @Test
    fun `happy path loads burn status`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("test-burn.yaml"), burnYaml)

        val registry = MutableStatusRegistry()
        val report = StatusEffectLoaderService(registry).loadFrom(dir)

        assertThat(report).isEqualTo(StatusEffectLoaderService.LoadReport(loaded = 1, failed = 0))
        val spec = registry.find(StatusEffectId("test-burn"))
        assertThat(spec).isNotNull
        requireNotNull(spec)
        assertThat(spec.kind).isEqualTo(StatusKind.BURN)
        assertThat(spec.magnitude).isEqualTo(1.5)
        assertThat(spec.stackable).isTrue()
        assertThat(spec.maxStacks).isEqualTo(3)
    }

    @Test
    fun `happy path loads speed status`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("test-speed.yaml"), speedYaml)

        val registry = MutableStatusRegistry()
        val report = StatusEffectLoaderService(registry).loadFrom(dir)

        assertThat(report.loaded).isEqualTo(1)
        val spec = registry.find(StatusEffectId("test-speed"))!!
        assertThat(spec.kind).isEqualTo(StatusKind.SPEED_UP)
        assertThat(spec.magnitude).isEqualTo(1.4)
    }

    @Test
    fun `happy path loads paralysis status with no scalar payload`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("test-paralysis.yaml"), paralysisYaml)

        val registry = MutableStatusRegistry()
        val report = StatusEffectLoaderService(registry).loadFrom(dir)

        assertThat(report.loaded).isEqualTo(1)
        val spec = registry.find(StatusEffectId("test-paralysis"))!!
        assertThat(spec.kind).isEqualTo(StatusKind.PARALYSIS)
        assertThat(spec.magnitude).isEqualTo(0.0)
    }

    @Test
    fun `kind-params mismatch fails the file but not the batch`(@TempDir dir: Path) {
        val mismatch = """
            id: bad-status
            display_name_key: astera.status.bad.name
            kind: burn
            default_duration_ticks: 60
            params:
              type: speed
              multiplier: 2.0
        """.trimIndent()
        Files.writeString(dir.resolve("bad.yaml"), mismatch)
        Files.writeString(dir.resolve("burn.yaml"), burnYaml)

        val registry = MutableStatusRegistry()
        val report = StatusEffectLoaderService(registry).loadFrom(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(1)
        assertThat(registry.find(StatusEffectId("bad-status"))).isNull()
        assertThat(registry.find(StatusEffectId("test-burn"))).isNotNull
    }

    @Test
    fun `missing directory returns zero counts`(@TempDir dir: Path) {
        val missing = dir.resolve("does-not-exist")
        val registry = MutableStatusRegistry()

        val report = StatusEffectLoaderService(registry).loadFrom(missing)

        assertThat(report).isEqualTo(StatusEffectLoaderService.LoadReport(loaded = 0, failed = 0))
    }
}
