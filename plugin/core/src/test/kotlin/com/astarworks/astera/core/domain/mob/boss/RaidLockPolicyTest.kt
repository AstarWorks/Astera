package com.astarworks.astera.core.domain.mob.boss

import com.astarworks.astera.core.domain.mob.raid.RaidLockPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class RaidLockPolicyTest {

    @Test
    fun `Open is the canonical OPEN sentinel`() {
        assertThat(RaidLockPolicy.OPEN).isSameAs(RaidLockPolicy.Open)
    }

    @Test
    fun `PartyLock accepts cap in 1 to 8`() {
        assertThat(RaidLockPolicy.PartyLock(1).maxPartySize).isEqualTo(1)
        assertThat(RaidLockPolicy.PartyLock(4).maxPartySize).isEqualTo(4)
        assertThat(RaidLockPolicy.PartyLock(8).maxPartySize).isEqualTo(8)
    }

    @Test
    fun `PartyLock rejects cap less than 1`() {
        assertThatThrownBy { RaidLockPolicy.PartyLock(0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { RaidLockPolicy.PartyLock(-1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `PartyLock rejects cap greater than 8`() {
        assertThatThrownBy { RaidLockPolicy.PartyLock(9) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Astera Phase 1 cap")
    }
}
