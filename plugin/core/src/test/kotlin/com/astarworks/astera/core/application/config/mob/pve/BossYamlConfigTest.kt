package com.astarworks.astera.core.application.config.mob.pve

import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.mob.boss.BossPhaseTransition
import com.astarworks.astera.core.domain.mob.raid.RaidLockPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BossYamlConfigTest {

    @Test
    fun `minimal boss config produces single-phase OPEN spec`() {
        val cfg = BossYamlConfig(
            id = "astera:test-boss",
            baseMobType = "astera:test-base",
            phases = listOf(BossPhaseYaml(index = 0, nameKey = "boss.test.phase.0")),
        )
        val (bossId, spec) = cfg.toEntry()
        assertThat(bossId.value).isEqualTo("astera:test-boss")
        assertThat(spec.baseMobType.value).isEqualTo("astera:test-base")
        assertThat(spec.phaseCount).isEqualTo(1)
        assertThat(spec.raidLock).isEqualTo(RaidLockPolicy.OPEN)
        assertThat(spec.transitions).isEmpty()
        assertThat(spec.recordKill).isTrue
    }

    @Test
    fun `multi-phase config with party lock and hp transition round-trips`() {
        val cfg = BossYamlConfig(
            id = "astera:scorched-titan",
            displayName = mapOf("en" to "Scorched Titan", "ja" to "焦熱の巨人"),
            baseMobType = "astera:scorched-titan-base",
            phases = listOf(
                BossPhaseYaml(
                    index = 0,
                    nameKey = "boss.scorched_titan.phase.molten",
                    periodicSkills = listOf("titan-stomp"),
                ),
                BossPhaseYaml(
                    index = 1,
                    nameKey = "boss.scorched_titan.phase.enraged",
                    immunityTicks = 60,
                    transitionCast = "titan-roar",
                    periodicSkills = listOf("titan-stomp", "titan-meteor"),
                    onHitSkills = listOf("titan-counter"),
                ),
            ),
            transitions = listOf(
                BossPhaseTransitionYaml(
                    type = "hp_threshold",
                    fromPhase = 0,
                    toPhase = 1,
                    hpFraction = 0.5,
                ),
            ),
            raidLock = RaidLockYaml(type = "party_lock", maxPartySize = 4),
            lootTable = "astera:scorched-titan-loot",
            recordKill = true,
        )
        val (_, spec) = cfg.toEntry()
        assertThat(spec.displayName.get(LocaleCode.EN)).isEqualTo("Scorched Titan")
        assertThat(spec.phaseCount).isEqualTo(2)

        val phase1 = spec.phase(1)!!
        assertThat(phase1.immunityTicks).isEqualTo(60)
        assertThat(phase1.transitionCast?.value).isEqualTo("titan-roar")
        assertThat(phase1.periodicSkills.map { it.value })
            .containsExactly("titan-stomp", "titan-meteor")
        assertThat(phase1.onHitSkills.map { it.value }).containsExactly("titan-counter")

        assertThat(spec.transitions).hasSize(1)
        val transition = spec.transitions[0] as BossPhaseTransition.HpThreshold
        assertThat(transition.hpFraction).isEqualTo(0.5)
        assertThat(transition.fromPhase).isEqualTo(0)
        assertThat(transition.toPhase).isEqualTo(1)

        assertThat(spec.raidLock).isInstanceOf(RaidLockPolicy.PartyLock::class.java)
        val lock = spec.raidLock as RaidLockPolicy.PartyLock
        assertThat(lock.maxPartySize).isEqualTo(4)

        assertThat(spec.lootTable.value).isEqualTo("astera:scorched-titan-loot")
    }

    @Test
    fun `hp_threshold transition without hp_fraction is rejected`() {
        val cfg = BossYamlConfig(
            id = "astera:bad-transition",
            baseMobType = "astera:base",
            phases = listOf(
                BossPhaseYaml(0, "p0"),
                BossPhaseYaml(1, "p1"),
            ),
            transitions = listOf(
                BossPhaseTransitionYaml(type = "hp_threshold", fromPhase = 0, toPhase = 1),
            ),
        )
        assertThatThrownBy { cfg.toEntry() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("hp_threshold requires hp_fraction")
    }

    @Test
    fun `unknown transition type is rejected`() {
        val cfg = BossYamlConfig(
            id = "astera:bad-trans",
            baseMobType = "astera:base",
            phases = listOf(
                BossPhaseYaml(0, "p0"),
                BossPhaseYaml(1, "p1"),
            ),
            transitions = listOf(
                BossPhaseTransitionYaml(type = "time_elapsed", fromPhase = 0, toPhase = 1),
            ),
        )
        assertThatThrownBy { cfg.toEntry() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("transitions.type='time_elapsed' unknown")
    }

    @Test
    fun `party_lock without max_party_size is rejected`() {
        val cfg = BossYamlConfig(
            id = "astera:bad-lock",
            baseMobType = "astera:base",
            phases = listOf(BossPhaseYaml(0, "p0")),
            raidLock = RaidLockYaml(type = "party_lock"),
        )
        assertThatThrownBy { cfg.toEntry() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("party_lock requires max_party_size")
    }

    @Test
    fun `raid_lock=open round-trips to RaidLockPolicy_Open`() {
        val cfg = BossYamlConfig(
            id = "astera:open-boss",
            baseMobType = "astera:base",
            phases = listOf(BossPhaseYaml(0, "p0")),
            raidLock = RaidLockYaml(type = "open"),
        )
        val (_, spec) = cfg.toEntry()
        assertThat(spec.raidLock).isEqualTo(RaidLockPolicy.Open)
    }

    @Test
    fun `phase 0 with transitionCast is rejected by BossPhase invariant`() {
        val cfg = BossYamlConfig(
            id = "astera:bad-phase-0",
            baseMobType = "astera:base",
            phases = listOf(
                BossPhaseYaml(0, "p0", transitionCast = "should-not-have-cast"),
            ),
        )
        assertThatThrownBy { cfg.toEntry() }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
