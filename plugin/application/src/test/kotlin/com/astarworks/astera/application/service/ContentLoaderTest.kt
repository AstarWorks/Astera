package com.astarworks.astera.application.service

import com.astarworks.astera.application.config.parser.JsonContentParser
import com.astarworks.astera.application.config.parser.TomlContentParser
import com.astarworks.astera.application.config.parser.YamlContentParser
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies the multi-format dispatch + failure-isolation behaviour of
 * [ContentLoader] (ADR-0016 §"Part 2"). Uses a tiny test-local DTO instead
 * of `WeaponYamlConfig` so the test doesn't depend on the full weapon
 * schema's evolution.
 */
class ContentLoaderTest {

    @Serializable
    data class TestDto(val id: String, val count: Int)

    private val parsers = listOf(
        YamlContentParser(),
        TomlContentParser(),
        JsonContentParser(),
    )

    private fun newLoader(): ContentLoader<TestDto> =
        ContentLoader(parsers, TestDto.serializer())

    @Test
    fun `happy path loads one valid YAML file`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("a.yaml"), "id: alpha\ncount: 7\n")

        val results = mutableListOf<TestDto>()
        val report = newLoader().loadFrom(dir) { results += it }

        assertThat(report).isEqualTo(ContentLoader.LoadReport(loaded = 1, failed = 0))
        assertThat(results).containsExactly(TestDto("alpha", 7))
    }

    @Test
    fun `equivalent YAML and TOML and JSON files produce the same DTO`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("y.yaml"), "id: same\ncount: 42\n")
        Files.writeString(dir.resolve("t.toml"), "id = \"same\"\ncount = 42\n")
        Files.writeString(dir.resolve("j.json"), """{"id":"same","count":42}""")

        val results = mutableListOf<TestDto>()
        val report = newLoader().loadFrom(dir) { results += it }

        assertThat(report).isEqualTo(ContentLoader.LoadReport(loaded = 3, failed = 0))
        assertThat(results).hasSize(3)
        // All three files describe the same DTO.
        assertThat(results.toSet()).containsExactly(TestDto("same", 42))
    }

    @Test
    fun `malformed file is counted as failed and other files still load`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("good.yaml"), "id: ok\ncount: 1\n")
        Files.writeString(dir.resolve("broken.yaml"), "id: : : oops\n  - not valid\n")
        Files.writeString(dir.resolve("good.json"), """{"id":"jay","count":2}""")

        val results = mutableListOf<TestDto>()
        val report = newLoader().loadFrom(dir) { results += it }

        assertThat(report.loaded).isEqualTo(2)
        assertThat(report.failed).isEqualTo(1)
        assertThat(results.map { it.id }).containsExactlyInAnyOrder("ok", "jay")
    }

    @Test
    fun `files with unknown extensions are silently ignored`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("a.yaml"), "id: alpha\ncount: 1\n")
        Files.writeString(dir.resolve("README.md"), "# not content")
        Files.writeString(dir.resolve("notes.txt"), "skip me")
        Files.writeString(dir.resolve("config.ini"), "[section]\nkey=value")

        val results = mutableListOf<TestDto>()
        val report = newLoader().loadFrom(dir) { results += it }

        assertThat(report).isEqualTo(ContentLoader.LoadReport(loaded = 1, failed = 0))
        assertThat(results).containsExactly(TestDto("alpha", 1))
    }

    @Test
    fun `missing directory returns zero report`(@TempDir dir: Path) {
        val missing = dir.resolve("does-not-exist")

        val results = mutableListOf<TestDto>()
        val report = newLoader().loadFrom(missing) { results += it }

        assertThat(report).isEqualTo(ContentLoader.LoadReport(loaded = 0, failed = 0))
        assertThat(results).isEmpty()
    }

    @Test
    fun `yml extension is accepted alongside yaml`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("a.yml"), "id: short\ncount: 9\n")

        val results = mutableListOf<TestDto>()
        val report = newLoader().loadFrom(dir) { results += it }

        assertThat(report).isEqualTo(ContentLoader.LoadReport(loaded = 1, failed = 0))
        assertThat(results).containsExactly(TestDto("short", 9))
    }
}
