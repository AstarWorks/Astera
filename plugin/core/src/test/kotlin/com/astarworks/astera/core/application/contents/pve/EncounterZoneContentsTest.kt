package com.astarworks.astera.core.application.contents.pve

import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.mob.MobRegionId
import com.astarworks.astera.core.domain.mob.encounter.DeathPenaltyRule
import com.astarworks.astera.core.domain.mob.encounter.DifficultyCurve
import com.astarworks.astera.core.domain.mob.encounter.EncounterMobEntry
import com.astarworks.astera.core.domain.mob.encounter.EncounterZone
import com.astarworks.astera.core.domain.mob.encounter.EncounterZoneId
import com.astarworks.astera.core.domain.mob.MobTypeId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class EncounterZoneContentsTest {

    @Test
    fun `register exposes via IEncounterZoneRegistry surface`() {
        val contents = EncounterZoneContents()
        val zone = EncounterZone(
            id = EncounterZoneId("astera:test"),
            displayName = LocalizedText(mapOf(LocaleCode.EN to "Test")),
            region = MobRegionId("test"),
            mobPool = listOf(EncounterMobEntry(MobTypeId("astera:wolf"), weight = 1)),
            maxConcurrent = 4,
        )
        contents.register(zone)
        assertThat(contents.get(EncounterZoneId("astera:test"))).isSameAs(zone)
        assertThat(contents.size()).isEqualTo(1)
        assertThat(contents.all()).containsExactly(zone)
    }

    @Test
    fun `bootstrap loads encounter zone YAML from disk`(@TempDir tmp: Path) {
        val yaml = tmp.resolve("blazing-pit.yaml")
        Files.writeString(
            yaml,
            """
            id: astera:blazing-pit
            display_name:
              en: "Blazing Pit"
            region: blazing-pit
            mob_pool:
              - mob_type_id: astera:ember-imp
                weight: 1
            max_concurrent: 4
            death_penalty:
              type: item_drop
              grace_ticks: 300
            """.trimIndent(),
        )
        val contents = EncounterZoneContents()
        val report = contents.bootstrap(tmp)
        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isZero
        val zone = contents.get(EncounterZoneId("astera:blazing-pit"))!!
        assertThat(zone.region.value).isEqualTo("blazing-pit")
        val penalty = zone.deathPenalty as DeathPenaltyRule.ItemDropOnDeath
        assertThat(penalty.graceTicks).isEqualTo(300)
        assertThat(zone.difficulty).isInstanceOf(DifficultyCurve.Flat::class.java)
    }

    @Test
    fun `failed YAML counted in report but registry intact`(@TempDir tmp: Path) {
        Files.writeString(
            tmp.resolve("good.yaml"),
            """
            id: astera:good-zone
            region: good
            """.trimIndent(),
        )
        Files.writeString(
            tmp.resolve("bad.yaml"),
            // Missing required `region` field
            """
            id: astera:bad-zone
            """.trimIndent(),
        )
        val contents = EncounterZoneContents()
        val report = contents.bootstrap(tmp)
        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(1)
        assertThat(contents.get(EncounterZoneId("astera:good-zone"))).isNotNull
        assertThat(contents.get(EncounterZoneId("astera:bad-zone"))).isNull()
    }
}
