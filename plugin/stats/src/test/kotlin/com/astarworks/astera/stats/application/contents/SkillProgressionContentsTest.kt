package com.astarworks.astera.stats.application.contents

import com.astarworks.astera.core.domain.progression.SkillKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Wave 5multi-skill (ADR-0101) — every shipped
 * `content/skills-progression/&#42;.yaml` must parse cleanly with no failed
 * loads. Catches schema drift.
 */
class SkillProgressionContentsTest {

    @Test
    fun `every shipped content YAML parses`() {
        val dir = Paths.get(System.getProperty("user.dir"))
            .parent.parent
            .resolve("content/skills-progression")
        val contents = SkillProgressionContents()
        val report = contents.bootstrap(dir)
        assertThat(report.failed)
            .withFailMessage(
                "Some skill-progression YAML failed to load (failed=${report.failed}, loaded=${report.loaded})",
            )
            .isEqualTo(0)
        // Every loaded definition must resolve to a known SkillKind.
        val resolved = contents.all().map { it.kind }
        assertThat(resolved).isSubsetOf(SkillKind.entries)
        // We ship at least the 4 axes the wave wired listeners for.
        assertThat(resolved).contains(
            SkillKind.Combat,
            SkillKind.Mining,
            SkillKind.BossSlayer,
            SkillKind.Trading,
        )
    }
}
