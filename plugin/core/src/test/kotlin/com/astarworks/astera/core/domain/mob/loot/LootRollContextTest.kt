package com.astarworks.astera.core.domain.mob.loot

import com.astarworks.astera.core.domain.mob.LootTableId
import com.astarworks.astera.core.domain.mob.MobTypeId
import com.astarworks.astera.core.domain.mob.boss.BossId
import com.astarworks.astera.core.domain.mob.encounter.EncounterZoneId
import com.astarworks.astera.core.domain.mob.runtime.MobId
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class LootRollContextTest {

    private val killer = PlayerId(UUID.randomUUID())
    private val mob = MobId.random()
    private val table = LootTableId("astera:test-drops")
    private val mobType = MobTypeId("astera:test-mob")

    private fun ctx(
        bossId: BossId? = null,
        zoneId: EncounterZoneId? = null,
        partyMemberIds: Set<PlayerId> = emptySet(),
        encounterLevel: Int = 1,
    ) = LootRollContext(
        tableId = table,
        killer = killer,
        mobId = mob,
        mobTypeId = mobType,
        bossId = bossId,
        zoneId = zoneId,
        partyMemberIds = partyMemberIds,
        encounterLevel = encounterLevel,
    )

    @Test
    fun `minimal valid context`() {
        val c = ctx()
        assertThat(c.isBossKill).isFalse
        assertThat(c.partyMemberIds).isEmpty()
        assertThat(c.encounterLevel).isEqualTo(1)
    }

    @Test
    fun `boss-kill context flagged`() {
        val c = ctx(bossId = BossId("astera:lich"))
        assertThat(c.isBossKill).isTrue
    }

    @Test
    fun `encounter level under 1 rejected`() {
        assertThatThrownBy { ctx(encounterLevel = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `killer in partyMemberIds rejected`() {
        assertThatThrownBy { ctx(partyMemberIds = setOf(killer)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must not include the killer")
    }

    @Test
    fun `LootRoll empty helper`() {
        val r = LootRoll.empty(seed = 42L)
        assertThat(r.isEmpty).isTrue
        assertThat(r.seed).isEqualTo(42L)
        assertThat(r.drops).isEmpty()
    }
}
