package com.astarworks.astera.core.domain.mob.boss

import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.mob.LootTableId
import com.astarworks.astera.core.domain.mob.MobTypeId
import com.astarworks.astera.core.domain.mob.raid.RaidLockPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BossSpecTest {

    private fun phase(index: Int) = BossPhase(
        index = index,
        nameKey = "boss.test.phase.$index",
    )

    private fun spec(
        phases: List<BossPhase> = listOf(phase(0)),
        transitions: List<BossPhaseTransition> = emptyList(),
    ) = BossSpec(
        id = BossId("astera:test-boss"),
        displayName = LocalizedText(mapOf(LocaleCode.EN to "Test Boss")),
        baseMobType = MobTypeId("astera:test-mob"),
        phases = phases,
        transitions = transitions,
    )

    @Test
    fun `single-phase spec is valid`() {
        val s = spec()
        assertThat(s.phaseCount).isEqualTo(1)
        assertThat(s.initialPhase.index).isEqualTo(0)
        assertThat(s.phase(0)).isNotNull
        assertThat(s.phase(1)).isNull()
    }

    @Test
    fun `multi-phase spec exposes ordered phases`() {
        val s = spec(phases = listOf(phase(0), phase(1), phase(2)))
        assertThat(s.phaseCount).isEqualTo(3)
        assertThat(s.phase(2)?.index).isEqualTo(2)
    }

    @Test
    fun `empty phases rejected at construction`() {
        assertThatThrownBy { spec(phases = emptyList()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("phases must not be empty")
    }

    @Test
    fun `first phase must be index 0`() {
        assertThatThrownBy { spec(phases = listOf(phase(1))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("first phase must have index 0")
    }

    @Test
    fun `phase indices must be ascending`() {
        assertThatThrownBy { spec(phases = listOf(phase(0), phase(2), phase(1))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("phase indices must be ascending")
    }

    @Test
    fun `phase indices must be unique`() {
        assertThatThrownBy { spec(phases = listOf(phase(0), phase(1), phase(1))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unique")
    }

    @Test
    fun `transitions referencing unknown phase rejected`() {
        assertThatThrownBy {
            spec(
                phases = listOf(phase(0), phase(1)),
                transitions = listOf(
                    BossPhaseTransition.HpThreshold(
                        fromPhase = 1,
                        toPhase = 9,
                        hpFraction = 0.3,
                    ),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unknown toPhase")
    }

    @Test
    fun `valid HP-threshold transitions accepted`() {
        val s = spec(
            phases = listOf(phase(0), phase(1), phase(2)),
            transitions = listOf(
                BossPhaseTransition.HpThreshold(fromPhase = 0, toPhase = 1, hpFraction = 0.66),
                BossPhaseTransition.HpThreshold(fromPhase = 1, toPhase = 2, hpFraction = 0.33),
            ),
        )
        assertThat(s.transitions).hasSize(2)
        assertThat(s.raidLock).isEqualTo(RaidLockPolicy.OPEN)
    }

    @Test
    fun `default loot table is EMPTY`() {
        val s = spec()
        assertThat(s.lootTable).isEqualTo(LootTableId.EMPTY)
        assertThat(s.recordKill).isTrue
    }
}
