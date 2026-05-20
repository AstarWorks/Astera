package com.astarworks.astera.combat.application.contents

import com.astarworks.astera.core.domain.status.StatusEffectId
import com.astarworks.astera.combat.domain.status.StatusEffectSpec
import com.astarworks.astera.combat.domain.status.StatusKind
import com.astarworks.astera.core.domain.i18n.MessageKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Migrated from `StatusEffectLoaderServiceTest` in Wave _infra-2b (ADR-0065).
 *
 * The legacy `MutableStatusRegistry` + `StatusEffectLoaderService` pair is
 * replaced by a single [StatusContents] that subclasses
 * [com.astarworks.astera.core.application.contents.YamlDynamicContents]. Adds
 * reload-semantics checks; the rest mirrors the legacy file to keep coverage.
 */
class StatusContentsTest {

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
    fun `bootstrap loads burn status`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("test-burn.yaml"), burnYaml)

        val contents = StatusContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(0)
        val spec = contents.find(StatusEffectId("test-burn"))
        assertThat(spec).isNotNull
        requireNotNull(spec)
        assertThat(spec.kind).isEqualTo(StatusKind.BURN)
        assertThat(spec.magnitude).isEqualTo(1.5)
        assertThat(spec.stackable).isTrue()
        assertThat(spec.maxStacks).isEqualTo(3)
    }

    @Test
    fun `bootstrap loads speed status`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("test-speed.yaml"), speedYaml)

        val contents = StatusContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(1)
        val spec = contents.find(StatusEffectId("test-speed"))!!
        assertThat(spec.kind).isEqualTo(StatusKind.SPEED_UP)
        assertThat(spec.magnitude).isEqualTo(1.4)
    }

    @Test
    fun `bootstrap loads paralysis status with no scalar payload`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("test-paralysis.yaml"), paralysisYaml)

        val contents = StatusContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(1)
        val spec = contents.find(StatusEffectId("test-paralysis"))!!
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

        val contents = StatusContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(1)
        assertThat(contents.find(StatusEffectId("bad-status"))).isNull()
        assertThat(contents.find(StatusEffectId("test-burn"))).isNotNull
    }

    @Test
    fun `missing directory returns zero counts and registry stays empty`(@TempDir dir: Path) {
        val missing = dir.resolve("does-not-exist")
        val contents = StatusContents()

        val report = contents.bootstrap(missing)

        assertThat(report.loaded).isEqualTo(0)
        assertThat(report.failed).isEqualTo(0)
        assertThat(contents.all()).isEmpty()
    }

    // ----- Reload semantics (Wave _infra-2b new) ---------------------------

    @Test
    fun `reload swaps to new map when all files parse`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("test-burn.yaml"), burnYaml)

        val contents = StatusContents()
        contents.bootstrap(dir)
        assertThat(contents.all()).hasSize(1)

        // Replace the single status with a different one
        Files.delete(dir.resolve("test-burn.yaml"))
        Files.writeString(dir.resolve("replacement.yaml"), burnYaml.replace("test-burn", "replacement"))

        val report = contents.reload(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(0)
        assertThat(contents.find(StatusEffectId("test-burn"))).isNull()
        assertThat(contents.find(StatusEffectId("replacement"))).isNotNull
        assertThat(contents.all()).hasSize(1)
    }

    @Test
    fun `reload is all-or-nothing - one bad file keeps old state intact`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("test-burn.yaml"), burnYaml)

        val contents = StatusContents()
        contents.bootstrap(dir)
        assertThat(contents.find(StatusEffectId("test-burn"))).isNotNull

        Files.writeString(dir.resolve("broken.yaml"), "id: : : not valid : yaml :::\n  - oops")

        val report = contents.reload(dir)

        // failed > 0 means reload aborts; old state preserved.
        assertThat(report.failed).isEqualTo(1)
        assertThat(contents.find(StatusEffectId("test-burn"))).isNotNull
        assertThat(contents.all()).hasSize(1)
    }

    @Test
    fun `register adds an entry without going through YAML`() {
        val contents = StatusContents()
        val spec = StatusEffectSpec(
            id = StatusEffectId("programmatic"),
            displayNameKey = MessageKey("test.name"),
            kind = StatusKind.SPEED_UP,
            magnitude = 1.25,
            stackable = false,
            maxStacks = 1,
        )

        contents.register(spec)

        assertThat(contents.find(StatusEffectId("programmatic"))).isEqualTo(spec)
    }
}
