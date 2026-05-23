package com.astarworks.astera.core.domain.mob.encounter

import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.mob.MobRegionId
import com.astarworks.astera.core.domain.mob.MobTypeId
import com.astarworks.astera.core.domain.mob.boss.BossId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class EncounterZoneTest {

    private fun zone(
        mobPool: List<EncounterMobEntry> = listOf(
            EncounterMobEntry(MobTypeId("astera:wolf"), weight = 3),
            EncounterMobEntry(MobTypeId("astera:skeleton"), weight = 1),
        ),
        boss: BossId? = null,
        maxConcurrent: Int = 10,
    ) = EncounterZone(
        id = EncounterZoneId("astera:blazing-pit"),
        displayName = LocalizedText(mapOf(LocaleCode.EN to "Blazing Pit")),
        region = MobRegionId("blazing-pit"),
        mobPool = mobPool,
        maxConcurrent = maxConcurrent,
        difficulty = DifficultyCurve.Flat,
        boss = boss,
    )

    @Test
    fun `valid zone construction exposes totals`() {
        val z = zone()
        assertThat(z.totalWeight).isEqualTo(4)
        assertThat(z.mobPool).hasSize(2)
        assertThat(z.boss).isNull()
    }

    @Test
    fun `negative maxConcurrent rejected`() {
        assertThatThrownBy { zone(maxConcurrent = -1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `duplicate MobTypeId entries rejected`() {
        assertThatThrownBy {
            zone(
                mobPool = listOf(
                    EncounterMobEntry(MobTypeId("astera:wolf"), weight = 1),
                    EncounterMobEntry(MobTypeId("astera:wolf"), weight = 2),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("duplicate")
    }

    @Test
    fun `EncounterMobEntry rejects zero or negative weight`() {
        assertThatThrownBy {
            EncounterMobEntry(MobTypeId("astera:wolf"), weight = 0)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            EncounterMobEntry(MobTypeId("astera:wolf"), weight = -1)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `boss-anchored zone exposes boss id`() {
        val z = zone(boss = BossId("astera:scorched-titan"))
        assertThat(z.boss).isEqualTo(BossId("astera:scorched-titan"))
    }

    @Test
    fun `Flat difficulty curve does not scale base`() {
        val curve = DifficultyCurve.Flat
        assertThat(curve.scale(100.0, level = 50)).isEqualTo(100.0)
    }

    @Test
    fun `Polynomial curve scales linearly with default exponent`() {
        val curve = DifficultyCurve.Polynomial(slope = 0.1)
        // level=1 -> delta=0 -> factor=1 -> unchanged
        assertThat(curve.scale(100.0, level = 1)).isEqualTo(100.0)
        // level=11 -> delta=10 -> factor=1 + 0.1*10 = 2 -> 200
        assertThat(curve.scale(100.0, level = 11)).isEqualTo(200.0)
    }

    @Test
    fun `Polynomial curve respects custom pivot`() {
        val curve = DifficultyCurve.Polynomial(slope = 0.5, pivot = 5)
        // level=5 -> delta=0 -> factor=1 -> base unchanged
        assertThat(curve.scale(20.0, level = 5)).isEqualTo(20.0)
        // level<pivot -> delta<=0 -> base unchanged
        assertThat(curve.scale(20.0, level = 3)).isEqualTo(20.0)
    }

    @Test
    fun `EncounterZoneId requires namespaced kebab-case`() {
        assertThatThrownBy { EncounterZoneId("invalid") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { EncounterZoneId("ASTERA:zone") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
