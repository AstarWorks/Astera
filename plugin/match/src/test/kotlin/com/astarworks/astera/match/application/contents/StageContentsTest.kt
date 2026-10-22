package com.astarworks.astera.match.application.contents

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.stage.Region
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.core.domain.stage.StageId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Migrated from `StageLoaderServiceTest` in Wave _infra-2b (ADR-0065).
 *
 * The legacy `InMemoryStageRepository` + `StageLoaderService` pair is replaced
 * by a single [StageContents] that subclasses
 * [com.astarworks.astera.core.application.contents.YamlDynamicContents]. The
 * test surface adds reload-semantics checks (Wave _infra-2b goal); the rest
 * of the cases mirror the legacy file 1:1 to keep coverage.
 */
class StageContentsTest {

    private val validYaml = """
        id: test-arena
        display_name_key: astera.stage.test-arena.name
        regions:
          - name: full-arena
            min: { x: -10.0, y: 60.0, z: -10.0 }
            max: { x:  10.0, y: 80.0, z:  10.0 }
    """.trimIndent()

    private val secondValidYaml = """
        id: another-arena
        display_name_key: astera.stage.another-arena.name
        regions:
          - name: spawn
            min: { x: 0.0, y: 64.0, z: 0.0 }
            max: { x: 4.0, y: 68.0, z: 4.0 }
    """.trimIndent()

    @Test
    fun `bootstrap loads one valid stage`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("test-arena.yaml"), validYaml)

        val contents = StageContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(0)
        val stage = contents.find(StageId("test-arena"))
        assertThat(stage).isNotNull
        requireNotNull(stage)
        assertThat(stage.displayNameKey).isEqualTo(MessageKey("astera.stage.test-arena.name"))
        assertThat(stage.regions).hasSize(1)
        assertThat(stage.regions[0].min).isEqualTo(Vec3(-10.0, 60.0, -10.0))
        assertThat(stage.regions[0].max).isEqualTo(Vec3(10.0, 80.0, 10.0))
    }

    @Test
    fun `bootstrap is best-effort - malformed yaml is skipped and other files still load`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("good.yaml"), validYaml)
        Files.writeString(dir.resolve("broken.yaml"), "id: : : not valid : yaml :::\n  - oops")

        val contents = StageContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(1)
        assertThat(contents.find(StageId("test-arena"))).isNotNull
        assertThat(contents.all()).hasSize(1)
    }

    @Test
    fun `bootstrap loads multiple yaml files`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("test-arena.yaml"), validYaml)
        Files.writeString(dir.resolve("another-arena.yaml"), secondValidYaml)

        val contents = StageContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(2)
        assertThat(report.failed).isEqualTo(0)
        assertThat(contents.all()).hasSize(2)
        assertThat(contents.find(StageId("test-arena"))).isNotNull
        assertThat(contents.find(StageId("another-arena"))).isNotNull
    }

    @Test
    fun `missing directory returns zero counts and registry stays empty`(@TempDir dir: Path) {
        val missing = dir.resolve("does-not-exist")
        val contents = StageContents()

        val report = contents.bootstrap(missing)

        assertThat(report.loaded).isEqualTo(0)
        assertThat(report.failed).isEqualTo(0)
        assertThat(contents.all()).isEmpty()
    }

    @Test
    fun `non-yaml files in the directory are ignored`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("good.yaml"), validYaml)
        Files.writeString(dir.resolve("README.md"), "not a stage")
        Files.writeString(dir.resolve("notes.txt"), "ignore me")

        val contents = StageContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(0)
    }

    @Test
    fun `yml extension is also accepted`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("test-arena.yml"), validYaml)

        val contents = StageContents()
        val report = contents.bootstrap(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(contents.find(StageId("test-arena"))).isNotNull
    }

    // ----- Reload semantics (Wave _infra-2b new) ---------------------------

    @Test
    fun `reload swaps to new map when all files parse`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("test-arena.yaml"), validYaml)

        val contents = StageContents()
        contents.bootstrap(dir)
        assertThat(contents.all()).hasSize(1)

        // Replace the single stage with a different one
        Files.delete(dir.resolve("test-arena.yaml"))
        Files.writeString(dir.resolve("replacement.yaml"), validYaml.replace("test-arena", "replacement"))

        val report = contents.reload(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(0)
        assertThat(contents.find(StageId("test-arena"))).isNull()
        assertThat(contents.find(StageId("replacement"))).isNotNull
        assertThat(contents.all()).hasSize(1)
    }

    @Test
    fun `reload is all-or-nothing - one bad file keeps old state intact`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("test-arena.yaml"), validYaml)

        val contents = StageContents()
        contents.bootstrap(dir)
        assertThat(contents.find(StageId("test-arena"))).isNotNull

        Files.writeString(dir.resolve("broken.yaml"), "id: : : not valid : yaml :::\n  - oops")

        val report = contents.reload(dir)

        // failed > 0 means reload aborts; old state preserved.
        assertThat(report.failed).isEqualTo(1)
        assertThat(contents.find(StageId("test-arena"))).isNotNull
        assertThat(contents.all()).hasSize(1)
    }

    @Test
    fun `register adds an entry without going through YAML`() {
        val contents = StageContents()
        val stage = Stage(
            id = StageId("programmatic"),
            displayNameKey = MessageKey("astera.stage.programmatic.name"),
            regions = listOf(
                Region(
                    min = Vec3(0.0, 0.0, 0.0),
                    max = Vec3(1.0, 1.0, 1.0),
                ),
            ),
        )

        contents.register(stage)

        assertThat(contents.find(StageId("programmatic"))).isEqualTo(stage)
    }

    @Test
    fun `pillars are loaded as a proper stage field`(@TempDir dir: Path) {
        Files.writeString(
            dir.resolve("pillar-arena.yaml"),
            """
            id: pillar-arena
            display_name_key: astera.stage.pillar-arena.name
            pillars:
              - { team: red, tier: 1, position: { x: 1.0, y: -60.0, z: 2.0 }, block: "minecraft:obsidian" }
              - { team: red, tier: 3, position: { x: 5.0, y: -60.0, z: 2.0 }, block: "minecraft:beacon", hp: 800 }
            """.trimIndent(),
        )
        val contents = StageContents()
        assertThat(contents.bootstrap(dir).loaded).isEqualTo(1)

        val pillars = requireNotNull(contents.find(StageId("pillar-arena"))).pillars
        assertThat(pillars.map { it.tier.ordinalValue }).containsExactly(1, 3)
        assertThat(pillars[0].team.value).isEqualTo("red")
        assertThat(pillars[0].position).isEqualTo(Vec3(1.0, -60.0, 2.0))
        assertThat(pillars[0].block.key).isEqualTo("minecraft:obsidian")
        assertThat(pillars[0].centre).isEqualTo(Vec3(1.5, -59.5, 2.5))
        assertThat(pillars[0].hp).isNull()
        assertThat(pillars[1].hp).isEqualTo(800)
    }

    @Test
    fun `a stage with a bad pillar tier or a duplicated pillar fails to load`(@TempDir dir: Path) {
        Files.writeString(
            dir.resolve("bad-tier.yaml"),
            """
            id: bad-tier
            display_name_key: astera.stage.bad-tier.name
            pillars:
              - { team: red, tier: 4, position: { x: 0.0, y: 0.0, z: 0.0 }, block: "minecraft:obsidian" }
            """.trimIndent(),
        )
        Files.writeString(
            dir.resolve("dup.yaml"),
            """
            id: dup
            display_name_key: astera.stage.dup.name
            pillars:
              - { team: red, tier: 1, position: { x: 0.0, y: 0.0, z: 0.0 }, block: "minecraft:obsidian" }
              - { team: red, tier: 1, position: { x: 1.0, y: 0.0, z: 0.0 }, block: "minecraft:obsidian" }
            """.trimIndent(),
        )
        val report = StageContents().bootstrap(dir)
        assertThat(report.loaded).isEqualTo(0)
        assertThat(report.failed).isEqualTo(2)
    }

    @Test
    fun `the shipped annihilation arena declares three tiers for each of its three teams`() {
        val dir = Path.of("../../content/stages").toAbsolutePath().normalize()
        val contents = StageContents()
        contents.bootstrap(dir)
        val arena = requireNotNull(contents.find(StageId("annihilation-arena")))
        assertThat(arena.pillars.groupBy { it.team.value }.mapValues { (_, v) -> v.map { it.tier.ordinalValue } })
            .containsOnlyKeys("red", "blue", "yellow")
            .allSatisfy { _, tiers -> assertThat(tiers).containsExactly(1, 2, 3) }
    }
}
