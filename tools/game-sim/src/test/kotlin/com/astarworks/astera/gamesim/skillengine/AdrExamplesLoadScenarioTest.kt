package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.compose.ExpansionDump
import com.astarworks.astera.core.domain.Result
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files

/** A1 (ADR-0303 section 12): every example a-j loads, expands deterministically, and its expansion is written to build/skill-expand/. */
class AdrExamplesLoadScenarioTest : SkillEngineScenario() {
    @Test
    fun everyExampleLoadsAndExpands() {
        val names = fixtureNames(examplesDir)
        assertThat(names).`as`("example fixtures a-j").hasSize(10)
        val problems = ArrayList<String>()
        for (name in names) {
            val text = Files.readString(examplesDir.resolve("$name.yaml"))
            val first = compileText("$name.yaml", text)
            val second = compileText("$name.yaml", text)
            if (first !is Result.Success || second !is Result.Success) {
                problems += "$name: ${(first as? Result.Failure)?.error ?: (second as? Result.Failure)?.error}"
                continue
            }
            val rendered = ExpansionDump.render(first.value.table)
            if (rendered != ExpansionDump.render(second.value.table)) problems += "$name: two compiles differ (not deterministic)"
            val reparsed = ExpansionDump.parse(rendered)
            if (reparsed == null || ExpansionDump.render(reparsed) != rendered) problems += "$name: dump does not round-trip through ExpansionDump.parse"

            // The file's own `# expand:` block is the reviewed truth where it is in dump grammar (a hand-written prose block is
            // recorded as such until W1-27 regenerates it).
            val block = dumpOf(name)
            val note = when {
                block.isEmpty() -> "no # expand: block in the fixture"
                ExpansionDump.parse(block) == null -> "# expand: block is prose, not dump grammar"
                else -> {
                    if (block.trimEnd() != rendered.trimEnd()) problems += "$name: compiled table differs from the # expand: block"
                    "# expand: block compared"
                }
            }
            writeEvidence(name, first.value.table, note)
        }
        assertThat(problems).`as`("A1 problems").isEmpty()
    }
}
