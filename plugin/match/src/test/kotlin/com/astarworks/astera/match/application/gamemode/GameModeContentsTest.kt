package com.astarworks.astera.match.application.gamemode

import com.astarworks.astera.match.application.contents.GameModeContents
import com.astarworks.astera.match.application.service.InMemoryGameModeFrameworkRegistry
import com.astarworks.astera.match.domain.mode.GameModeId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * ADR-0106 Wave 5gamemode — [GameModeContents] YAML bootstrap + reflective
 * instantiation tests. The reflection path is the Phase 1 PoC route; Phase 3
 * (Koin DI) replaces it. Pinning the happy-path + every documented failure
 * mode here so the dispatcher behaviour stays auditable.
 */
class GameModeContentsTest {

    @Test
    fun `bootstrap loads norma yaml and registerInto produces NormaGameMode singleton`(@TempDir tmp: Path) {
        writeYaml(tmp, "norma.yaml", NORMA_YAML)

        val contents = GameModeContents()
        val report = contents.bootstrap(tmp)
        assertThat(report.failed).isEqualTo(0)
        assertThat(report.loaded).isEqualTo(1)

        val registry = InMemoryGameModeFrameworkRegistry()
        val warns = mutableListOf<String>()
        val registered = contents.registerInto(registry) { warns += it }

        assertThat(warns).isEmpty()
        assertThat(registered).containsExactly(GameModeId("norma"))
        assertThat(registry.get(GameModeId("norma"))).isNotNull
        assertThat(registry.get(GameModeId("norma"))!!.id().value).isEqualTo("norma")
    }

    @Test
    fun `registerInto warns and skips when impl_class is missing on classpath`(@TempDir tmp: Path) {
        writeYaml(
            tmp,
            "ghost.yaml",
            """
            id: ghost
            title_key: astera.gamemode.ghost.title
            impl_class: com.astarworks.astera.match.application.gamemode.NoSuchClass
            tick_period_ticks: 20
            default_config: {}
            """.trimIndent(),
        )

        val contents = GameModeContents().also { it.bootstrap(tmp) }
        val registry = InMemoryGameModeFrameworkRegistry()
        val warns = mutableListOf<String>()

        val registered = contents.registerInto(registry) { warns += it }

        assertThat(registered).isEmpty()
        assertThat(registry.all()).isEmpty()
        assertThat(warns).hasSize(1)
        assertThat(warns[0]).contains("not found on classpath").contains("ghost")
    }

    @Test
    fun `registerInto warns and skips when impl reports a different id`(@TempDir tmp: Path) {
        // Re-using NormaGameMode (id 'norma') but YAML claims id 'wrong-id' →
        // the spec/impl id mismatch must be caught and skipped at registerInto.
        writeYaml(
            tmp,
            "wrong.yaml",
            """
            id: wrong-id
            title_key: astera.gamemode.wrong.title
            impl_class: com.astarworks.astera.match.application.gamemode.NormaGameMode
            tick_period_ticks: 20
            default_config: {}
            """.trimIndent(),
        )

        val contents = GameModeContents().also { it.bootstrap(tmp) }
        val registry = InMemoryGameModeFrameworkRegistry()
        val warns = mutableListOf<String>()

        val registered = contents.registerInto(registry) { warns += it }

        assertThat(registered).isEmpty()
        assertThat(registry.all()).isEmpty()
        assertThat(warns).hasSize(1)
        assertThat(warns[0]).contains("does not match").contains("wrong-id").contains("norma")
    }

    @Test
    fun `bootstrap rejects YAML with blank impl_class`(@TempDir tmp: Path) {
        writeYaml(
            tmp,
            "broken.yaml",
            """
            id: broken
            title_key: astera.gamemode.broken.title
            impl_class: ''
            tick_period_ticks: 20
            default_config: {}
            """.trimIndent(),
        )

        val contents = GameModeContents()
        val report = contents.bootstrap(tmp)
        // Blank impl_class → require() inside toSpec() throws → ContentLoader counts as failed.
        assertThat(report.failed).isEqualTo(1)
        assertThat(report.loaded).isEqualTo(0)
    }

    private fun writeYaml(dir: Path, name: String, body: String) {
        Files.writeString(dir.resolve(name), body)
    }

    private companion object {
        const val NORMA_YAML = """
id: norma
title_key: astera.gamemode.norma.title
impl_class: com.astarworks.astera.match.application.gamemode.NormaGameMode
tick_period_ticks: 20
default_config: {}
"""
    }
}
