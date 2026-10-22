package com.astarworks.astera.architecture

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Every ADR file under `docs/adr/` must own a unique 4-digit number (`NNNN-slug.md`).
 * Two files with the same number make every `ADR-NNNN` reference ambiguous.
 */
class AdrNumberUniquenessTest {

    private val root: Path = generateSequence(Paths.get("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }

    @Test
    fun `no two ADR files share a number`() {
        val adrFile = Regex("^(\\d{4})-.+\\.md$")
        val names = Files.list(root.resolve("docs/adr")).use { files ->
            files.map { it.fileName.toString() }.toList()
        }
        val byNumber = names
            .mapNotNull { name -> adrFile.find(name)?.let { it.groupValues[1] to name } }
            .groupBy({ it.first }, { it.second })

        val duplicates = byNumber.filterValues { it.size > 1 }
            .map { (number, files) -> "ADR-$number: ${files.sorted().joinToString(", ")}" }
            .sorted()

        assertThat(duplicates).`as`("ADR numbers used by more than one file in docs/adr").isEmpty()
    }
}
