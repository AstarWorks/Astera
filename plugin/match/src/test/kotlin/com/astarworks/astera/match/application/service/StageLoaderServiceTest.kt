package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.match.domain.stage.StageId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Mirrors `WeaponLoaderServiceTest` — three required scenarios plus a couple
 * of extras that fall out naturally from the [InMemoryStageRepository] +
 * [ContentLoader][com.astarworks.astera.core.application.service.ContentLoader]
 * pairing.
 */
class StageLoaderServiceTest {

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
    fun `happy path loads one valid stage`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("test-arena.yaml"), validYaml)

        val repo = InMemoryStageRepository()
        val report = StageLoaderService(repo).loadFrom(dir)

        assertThat(report).isEqualTo(StageLoaderService.LoadReport(loaded = 1, failed = 0))
        val stage = repo.findById(StageId("test-arena"))
        assertThat(stage).isNotNull
        requireNotNull(stage)
        assertThat(stage.displayNameKey).isEqualTo(MessageKey("astera.stage.test-arena.name"))
        assertThat(stage.regions).hasSize(1)
        assertThat(stage.regions[0].min).isEqualTo(Vec3(-10.0, 60.0, -10.0))
        assertThat(stage.regions[0].max).isEqualTo(Vec3(10.0, 80.0, 10.0))
    }

    @Test
    fun `missing required field is skipped and counted as failed`(@TempDir dir: Path) {
        // `display_name_key` is required (no default); kaml must reject it.
        val brokenYaml = """
            id: broken-arena
            regions:
              - name: full
                min: { x: 0.0, y: 0.0, z: 0.0 }
                max: { x: 1.0, y: 1.0, z: 1.0 }
        """.trimIndent()
        Files.writeString(dir.resolve("broken.yaml"), brokenYaml)
        Files.writeString(dir.resolve("good.yaml"), validYaml)

        val repo = InMemoryStageRepository()
        val report = StageLoaderService(repo).loadFrom(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(1)
        assertThat(repo.findById(StageId("test-arena"))).isNotNull
        assertThat(repo.findById(StageId("broken-arena"))).isNull()
        assertThat(repo.all()).hasSize(1)
    }

    @Test
    fun `multiple yaml files are all loaded into the repository`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("test-arena.yaml"), validYaml)
        Files.writeString(dir.resolve("another-arena.yaml"), secondValidYaml)

        val repo = InMemoryStageRepository()
        val report = StageLoaderService(repo).loadFrom(dir)

        assertThat(report).isEqualTo(StageLoaderService.LoadReport(loaded = 2, failed = 0))
        assertThat(repo.all()).hasSize(2)
        assertThat(repo.findById(StageId("test-arena"))).isNotNull
        assertThat(repo.findById(StageId("another-arena"))).isNotNull
    }

    @Test
    fun `missing directory returns zero counts and repository stays empty`(@TempDir dir: Path) {
        val missing = dir.resolve("does-not-exist")
        val repo = InMemoryStageRepository()

        val report = StageLoaderService(repo).loadFrom(missing)

        assertThat(report).isEqualTo(StageLoaderService.LoadReport(loaded = 0, failed = 0))
        assertThat(repo.all()).isEmpty()
    }

    @Test
    fun `non-yaml files in the directory are ignored`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("good.yaml"), validYaml)
        Files.writeString(dir.resolve("README.md"), "not a stage")
        Files.writeString(dir.resolve("notes.txt"), "ignore me")

        val repo = InMemoryStageRepository()
        val report = StageLoaderService(repo).loadFrom(dir)

        assertThat(report).isEqualTo(StageLoaderService.LoadReport(loaded = 1, failed = 0))
        assertThat(repo.findById(StageId("test-arena"))).isNotNull
    }

    @Test
    fun `yml extension is also accepted`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("test-arena.yml"), validYaml)

        val repo = InMemoryStageRepository()
        val report = StageLoaderService(repo).loadFrom(dir)

        assertThat(report.loaded).isEqualTo(1)
        assertThat(repo.findById(StageId("test-arena"))).isNotNull
    }
}
