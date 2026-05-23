package com.astarworks.astera.core.domain.mob.boss

import com.astarworks.astera.core.domain.skill.SkillId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BossPhaseTest {

    @Test
    fun `initial phase 0 has no transition cast`() {
        val p = BossPhase(index = 0, nameKey = "boss.x.phase.0")
        assertThat(p.transitionCast).isNull()
    }

    @Test
    fun `non-initial phase may declare transition cast`() {
        val p = BossPhase(
            index = 1,
            nameKey = "boss.x.phase.1",
            transitionCast = SkillId("boss-enrage"),
        )
        assertThat(p.transitionCast).isEqualTo(SkillId("boss-enrage"))
    }

    @Test
    fun `index 0 with transition cast is rejected`() {
        assertThatThrownBy {
            BossPhase(
                index = 0,
                nameKey = "boss.x.phase.0",
                transitionCast = SkillId("boss-enrage"),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("initial phase")
    }

    @Test
    fun `negative immunity ticks rejected`() {
        assertThatThrownBy {
            BossPhase(index = 0, nameKey = "boss.x", immunityTicks = -1)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `blank name key rejected`() {
        assertThatThrownBy {
            BossPhase(index = 0, nameKey = "  ")
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
