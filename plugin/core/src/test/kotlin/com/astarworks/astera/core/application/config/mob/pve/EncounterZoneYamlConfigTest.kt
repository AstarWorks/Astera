package com.astarworks.astera.core.application.config.mob.pve

import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.mob.encounter.DeathPenaltyRule
import com.astarworks.astera.core.domain.mob.encounter.DifficultyCurve
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class EncounterZoneYamlConfigTest {

    @Test
    fun `minimal config defaults difficulty Flat and death-penalty SafeDeath`() {
        val cfg = EncounterZoneYamlConfig(
            id = "astera:test-zone",
            region = "test-region",
        )
        val (zoneId, zone) = cfg.toEntry()
        assertThat(zoneId.value).isEqualTo("astera:test-zone")
        assertThat(zone.region.value).isEqualTo("test-region")
        assertThat(zone.difficulty).isInstanceOf(DifficultyCurve.Flat::class.java)
        assertThat(zone.deathPenalty).isEqualTo(DeathPenaltyRule.SafeDeath)
        assertThat(zone.boss).isNull()
        assertThat(zone.mobPool).isEmpty()
    }

    @Test
    fun `full config round-trips display name + mob pool + polynomial difficulty + boss + item drop`() {
        val cfg = EncounterZoneYamlConfig(
            id = "astera:blazing-pit",
            displayName = mapOf("en" to "Blazing Pit", "ja" to "燃え盛る坑道"),
            region = "blazing-pit",
            mobPool = listOf(
                EncounterMobEntryYaml(mobTypeId = "astera:ember-imp", weight = 70),
                EncounterMobEntryYaml(mobTypeId = "astera:flame-jackal", weight = 30),
            ),
            maxConcurrent = 12,
            difficulty = DifficultyCurveYaml(
                type = "polynomial",
                slope = 0.1,
                pivot = 1,
                exponent = 1.2,
            ),
            boss = "astera:scorched-titan",
            deathPenalty = DeathPenaltyYaml(type = "item_drop", graceTicks = 600),
        )
        val (_, zone) = cfg.toEntry()
        assertThat(zone.displayName.get(LocaleCode.EN)).isEqualTo("Blazing Pit")
        assertThat(zone.displayName.get(LocaleCode.JA)).isEqualTo("燃え盛る坑道")
        assertThat(zone.mobPool).hasSize(2)
        assertThat(zone.mobPool[0].mobTypeId.value).isEqualTo("astera:ember-imp")
        assertThat(zone.mobPool[0].weight).isEqualTo(70)
        assertThat(zone.maxConcurrent).isEqualTo(12)
        assertThat(zone.difficulty).isInstanceOf(DifficultyCurve.Polynomial::class.java)
        val poly = zone.difficulty as DifficultyCurve.Polynomial
        assertThat(poly.slope).isEqualTo(0.1)
        assertThat(poly.pivot).isEqualTo(1)
        assertThat(poly.exponent).isEqualTo(1.2)
        assertThat(zone.boss?.value).isEqualTo("astera:scorched-titan")
        assertThat(zone.deathPenalty)
            .isInstanceOf(DeathPenaltyRule.ItemDropOnDeath::class.java)
        val penalty = zone.deathPenalty as DeathPenaltyRule.ItemDropOnDeath
        assertThat(penalty.graceTicks).isEqualTo(600)
    }

    @Test
    fun `death_penalty=safe alias resolves to SafeDeath`() {
        val cfg = EncounterZoneYamlConfig(
            id = "astera:safe-zone",
            region = "safe-zone",
            deathPenalty = DeathPenaltyYaml(type = "safe_death"),
        )
        val (_, zone) = cfg.toEntry()
        assertThat(zone.deathPenalty).isEqualTo(DeathPenaltyRule.SafeDeath)
    }

    @Test
    fun `unknown death_penalty type is rejected`() {
        val cfg = EncounterZoneYamlConfig(
            id = "astera:bad-zone",
            region = "bad-zone",
            deathPenalty = DeathPenaltyYaml(type = "consume-soul"),
        )
        assertThatThrownBy { cfg.toEntry() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("death_penalty.type='consume-soul' unknown")
    }

    @Test
    fun `unknown difficulty type is rejected`() {
        val cfg = EncounterZoneYamlConfig(
            id = "astera:bad-difficulty",
            region = "bad-difficulty",
            difficulty = DifficultyCurveYaml(type = "exponential"),
        )
        assertThatThrownBy { cfg.toEntry() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("difficulty.type='exponential' unknown")
    }

    @Test
    fun `polynomial difficulty without slope is rejected`() {
        val cfg = EncounterZoneYamlConfig(
            id = "astera:bad-poly",
            region = "bad-poly",
            difficulty = DifficultyCurveYaml(type = "polynomial"),
        )
        assertThatThrownBy { cfg.toEntry() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("polynomial requires slope")
    }

    @Test
    fun `default item_drop uses 600-tick grace`() {
        val cfg = EncounterZoneYamlConfig(
            id = "astera:default-drop",
            region = "default-drop",
            deathPenalty = DeathPenaltyYaml(type = "item_drop"),
        )
        val (_, zone) = cfg.toEntry()
        val rule = zone.deathPenalty as DeathPenaltyRule.ItemDropOnDeath
        assertThat(rule.graceTicks)
            .isEqualTo(DeathPenaltyRule.ItemDropOnDeath.DEFAULT_GRACE_TICKS)
    }
}
