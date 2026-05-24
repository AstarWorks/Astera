package com.astarworks.astera.core.application.config.mob.pve

import com.astarworks.astera.core.domain.mob.boss.BossId
import com.astarworks.astera.core.domain.mob.boss.BossPhaseTransition
import com.astarworks.astera.core.domain.skill.SkillId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Tests the Wave 5combat-depth (ADR-0105) additions to
 * [BossPhaseTransitionYaml]: TimerExpired / MultiPlayerThreshold /
 * MechanicCompleted / AllOf decoding paths. The original `hp_threshold`
 * decoding is covered in `BossYamlConfigTest`.
 */
class BossYamlConfigCombatDepthTest {

    private val bossId = BossId("astera:test-boss")

    @Test
    fun `timer_expired decodes to TimerExpired`() {
        val yaml = BossPhaseTransitionYaml(
            type = "timer_expired",
            fromPhase = 0,
            toPhase = 1,
            elapsedTicks = 1800L,
        )
        val domain = yaml.toDomain(bossId)
        assertThat(domain).isInstanceOf(BossPhaseTransition.TimerExpired::class.java)
        domain as BossPhaseTransition.TimerExpired
        assertThat(domain.elapsedTicks).isEqualTo(1800L)
    }

    @Test
    fun `multi_player_threshold decodes to MultiPlayerThreshold`() {
        val yaml = BossPhaseTransitionYaml(
            type = "multi_player_threshold",
            fromPhase = 0,
            toPhase = 1,
            minPlayers = 5,
        )
        val domain = yaml.toDomain(bossId)
        assertThat(domain).isInstanceOf(BossPhaseTransition.MultiPlayerThreshold::class.java)
        domain as BossPhaseTransition.MultiPlayerThreshold
        assertThat(domain.minPlayers).isEqualTo(5)
    }

    @Test
    fun `mechanic_completed decodes to MechanicCompleted`() {
        val yaml = BossPhaseTransitionYaml(
            type = "mechanic_completed",
            fromPhase = 0,
            toPhase = 1,
            mechanicSkillId = "titan-vulnerability",
        )
        val domain = yaml.toDomain(bossId)
        assertThat(domain).isInstanceOf(BossPhaseTransition.MechanicCompleted::class.java)
        domain as BossPhaseTransition.MechanicCompleted
        assertThat(domain.mechanicSkillId).isEqualTo(SkillId("titan-vulnerability"))
    }

    @Test
    fun `all_of decodes children and propagates from-to`() {
        val yaml = BossPhaseTransitionYaml(
            type = "all_of",
            fromPhase = 1,
            toPhase = 2,
            children = listOf(
                BossPhaseTransitionYaml(
                    type = "hp_threshold",
                    fromPhase = 0,
                    toPhase = 0,
                    hpFraction = 0.3,
                ),
                BossPhaseTransitionYaml(
                    type = "mechanic_completed",
                    fromPhase = 0,
                    toPhase = 0,
                    mechanicSkillId = "titan-vulnerability",
                ),
            ),
        )
        val domain = yaml.toDomain(bossId)
        assertThat(domain).isInstanceOf(BossPhaseTransition.AllOf::class.java)
        domain as BossPhaseTransition.AllOf
        assertThat(domain.fromPhase).isEqualTo(1)
        assertThat(domain.toPhase).isEqualTo(2)
        assertThat(domain.children).hasSize(2)
        // Children should have inherited from_phase/to_phase from the parent.
        assertThat(domain.children[0].fromPhase).isEqualTo(1)
        assertThat(domain.children[1].toPhase).isEqualTo(2)
    }

    @Test
    fun `unknown type throws helpful message`() {
        val yaml = BossPhaseTransitionYaml(
            type = "moon_phase",
            fromPhase = 0,
            toPhase = 1,
        )
        assertThatThrownBy { yaml.toDomain(bossId) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("expected hp_threshold")
            .hasMessageContaining("all_of")
    }

    @Test
    fun `timer_expired requires elapsed_ticks`() {
        val yaml = BossPhaseTransitionYaml(
            type = "timer_expired",
            fromPhase = 0,
            toPhase = 1,
        )
        assertThatThrownBy { yaml.toDomain(bossId) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("requires elapsed_ticks")
    }
}
