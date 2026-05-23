package com.astarworks.astera.stats.domain.progression

import com.astarworks.astera.core.domain.progression.SkillKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Wave 5multi-skill (ADR-0101) — token parsing + axis-count contract.
 */
class SkillKindTest {

    @Test
    fun `fromToken parses every defined kind`() {
        SkillKind.entries.forEach { kind ->
            assertThat(SkillKind.fromToken(kind.token)).isEqualTo(kind)
        }
    }

    @Test
    fun `fromToken is case-insensitive and trims whitespace`() {
        assertThat(SkillKind.fromToken(" COMBAT ")).isEqualTo(SkillKind.Combat)
        assertThat(SkillKind.fromToken("BOSS_SLAYER")).isEqualTo(SkillKind.BossSlayer)
        assertThat(SkillKind.fromToken("mining")).isEqualTo(SkillKind.Mining)
    }

    @Test
    fun `fromToken returns null for unknown`() {
        assertThat(SkillKind.fromToken("magic")).isNull()
        assertThat(SkillKind.fromToken("")).isNull()
    }

    @Test
    fun `AXIS_COUNT is 10 per ADR-0101`() {
        assertThat(SkillKind.AXIS_COUNT).isEqualTo(10)
    }

    @Test
    fun `every kind has a unique non-blank token`() {
        val tokens = SkillKind.entries.map { it.token }
        assertThat(tokens).doesNotHaveDuplicates()
        tokens.forEach { token ->
            assertThat(token).isNotBlank()
            assertThat(token).matches(Regex("^[a-z][a-z0-9_]*$").pattern)
        }
    }
}
