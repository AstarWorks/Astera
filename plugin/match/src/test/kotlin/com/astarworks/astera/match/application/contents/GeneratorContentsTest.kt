package com.astarworks.astera.match.application.contents

import com.astarworks.astera.match.application.config.AffiliationYamlConfig
import com.astarworks.astera.match.application.config.GeneratorYamlConfig
import com.astarworks.astera.match.application.config.Vec3YamlConfig
import com.astarworks.astera.match.domain.generator.GeneratorId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * New in Wave _infra-2b (ADR-0065). [GeneratorContents] holds authored
 * `GeneratorYamlConfig` templates loaded from `content/generators/`; the
 * runtime per-match `StarGenerator` instances live in
 * [com.astarworks.astera.match.application.port.outbound.IGeneratorRepository]
 * (untouched by this wave).
 *
 * The 3 reload-semantics tests (atomic swap, abort-on-failure, programmatic
 * register) mirror the `WeaponContentsTest` shape (ADR-0060).
 */
class GeneratorContentsTest {

    private val validYaml = """
        id: arena-center
        match_stage_id: example-arena
        position: { x: 0.0, y: 64.0, z: 0.0 }
        currency: STAR
        period_seconds: 30.0
        amount: 1
        tier: iron
        affiliation:
          kind: neutral
    """.trimIndent()

    private val secondValidYaml = """
        id: red-base
        match_stage_id: example-arena
        position: { x: -50.0, y: 64.0, z: 0.0 }
        currency: STAR
        period_seconds: 20.0
        amount: 2
        tier: gold
        affiliation:
          kind: team
          team_id: red
    """.trimIndent()

    @Test
    fun `bootstrap loads one valid generator`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("arena-center.yaml"), validYaml)

        val contents = GeneratorContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(0)
        val cfg = contents.find(GeneratorId("arena-center"))
        assertThat(cfg).isNotNull
        requireNotNull(cfg)
        assertThat(cfg.id).isEqualTo("arena-center")
        assertThat(cfg.matchStageId).isEqualTo("example-arena")
        assertThat(cfg.currency).isEqualTo("STAR")
        assertThat(cfg.periodSeconds).isEqualTo(30.0)
        assertThat(cfg.amount).isEqualTo(1)
        assertThat(cfg.tier).isEqualTo("iron")
        assertThat(cfg.affiliation.kind).isEqualTo("neutral")
    }

    @Test
    fun `bootstrap is best-effort - malformed yaml is skipped and other files still load`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("good.yaml"), validYaml)
        Files.writeString(dir.resolve("broken.yaml"), "id: : : not valid : yaml :::\n  - oops")

        val contents = GeneratorContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(1)
        assertThat(contents.find(GeneratorId("arena-center"))).isNotNull
        assertThat(contents.all()).hasSize(1)
    }

    @Test
    fun `bootstrap fails when id violates the kebab regex`(@TempDir dir: Path) {
        // ID lexical violations surface inside toEntry -> GeneratorId(...) and
        // ContentLoader counts the file as failed.
        val badYaml = validYaml.replace("id: arena-center", "id: ArenaCenter")
        Files.writeString(dir.resolve("bad.yaml"), badYaml)

        val contents = GeneratorContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(0)
        assertThat(report.failed).isEqualTo(1)
        assertThat(contents.all()).isEmpty()
    }

    @Test
    fun `missing directory returns zero counts and registry stays empty`(@TempDir dir: Path) {
        val missing = dir.resolve("does-not-exist")
        val contents = GeneratorContents()

        val report = contents.bootstrap(missing)

        assertThat(report.loaded).isEqualTo(0)
        assertThat(report.failed).isEqualTo(0)
        assertThat(contents.all()).isEmpty()
    }

    // ----- Reload semantics (Wave _infra-2b new) ---------------------------

    @Test
    fun `reload swaps to new map when all files parse`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("arena-center.yaml"), validYaml)

        val contents = GeneratorContents()
        contents.bootstrap(dir)
        assertThat(contents.all()).hasSize(1)

        Files.delete(dir.resolve("arena-center.yaml"))
        Files.writeString(dir.resolve("red-base.yaml"), secondValidYaml)

        val report = contents.reload(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(0)
        assertThat(contents.find(GeneratorId("arena-center"))).isNull()
        assertThat(contents.find(GeneratorId("red-base"))).isNotNull
        assertThat(contents.all()).hasSize(1)
    }

    @Test
    fun `reload is all-or-nothing - one bad file keeps old state intact`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("arena-center.yaml"), validYaml)

        val contents = GeneratorContents()
        contents.bootstrap(dir)
        assertThat(contents.find(GeneratorId("arena-center"))).isNotNull

        Files.writeString(dir.resolve("broken.yaml"), "id: : : not valid : yaml :::\n  - oops")

        val report = contents.reload(dir)

        // failed > 0 means reload aborts; old state preserved.
        assertThat(report.failed).isEqualTo(1)
        assertThat(contents.find(GeneratorId("arena-center"))).isNotNull
        assertThat(contents.all()).hasSize(1)
    }

    @Test
    fun `register adds an entry without going through YAML`() {
        val contents = GeneratorContents()
        val cfg = GeneratorYamlConfig(
            id = "programmatic",
            matchStageId = "example-arena",
            position = Vec3YamlConfig(0.0, 64.0, 0.0),
            currency = "STAR",
            periodSeconds = 30.0,
            amount = 1,
            tier = "iron",
            affiliation = AffiliationYamlConfig(kind = "neutral"),
        )

        contents.register(cfg)

        assertThat(contents.find(GeneratorId("programmatic"))).isEqualTo(cfg)
    }
}
