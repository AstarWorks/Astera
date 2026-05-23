package com.astarworks.astera.stats.domain.progression

import com.astarworks.astera.core.domain.progression.SkillKind
import com.astarworks.astera.core.domain.progression.SkillPerkEffect
import com.astarworks.astera.core.domain.progression.SkillProgressionDefinition
import com.astarworks.astera.core.domain.progression.SkillProgressionPerk
import com.astarworks.astera.core.domain.progression.SkillXpCurve
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Wave 5multi-skill (ADR-0101) — perk + curve invariant cross-validation.
 */
class SkillProgressionDefinitionTest {

    @Test
    fun `accepts perks within curve maxLevel`() {
        val curve = SkillXpCurve(thresholds = listOf(100L, 250L, 500L)) // maxLevel = 4
        val perks = listOf(
            SkillProgressionPerk(
                id = "perk-a",
                level = 2,
                effect = SkillPerkEffect.StatMultiplier("damage", 1.05),
            ),
            SkillProgressionPerk(
                id = "perk-b",
                level = 4,
                effect = SkillPerkEffect.StatAdditive("crit", 0.05),
            ),
        )
        val def = SkillProgressionDefinition(SkillKind.Combat, curve, perks)
        assertThat(def.perks).hasSize(2)
    }

    @Test
    fun `rejects perks beyond curve maxLevel`() {
        val curve = SkillXpCurve(thresholds = listOf(100L)) // maxLevel = 2
        val perks = listOf(
            SkillProgressionPerk(
                id = "perk-overcap",
                level = 5,
                effect = SkillPerkEffect.UnlockFeature("foo"),
            ),
        )
        assertThatThrownBy { SkillProgressionDefinition(SkillKind.Mining, curve, perks) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("perk-overcap")
    }

    @Test
    fun `rejects duplicate perk ids`() {
        val curve = SkillXpCurve(thresholds = listOf(100L, 250L))
        val perks = listOf(
            SkillProgressionPerk(
                id = "dupe",
                level = 2,
                effect = SkillPerkEffect.StatMultiplier("damage", 1.05),
            ),
            SkillProgressionPerk(
                id = "dupe",
                level = 3,
                effect = SkillPerkEffect.StatMultiplier("damage", 1.10),
            ),
        )
        assertThatThrownBy { SkillProgressionDefinition(SkillKind.Combat, curve, perks) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("dupe")
    }

    @Test
    fun `rejects invalid perk id`() {
        assertThatThrownBy {
            SkillProgressionPerk(
                id = "Invalid_ID",
                level = 5,
                effect = SkillPerkEffect.StatMultiplier("damage", 1.05),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects zero or negative multiplier`() {
        assertThatThrownBy { SkillPerkEffect.StatMultiplier("damage", 0.0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { SkillPerkEffect.StatMultiplier("damage", -1.0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
