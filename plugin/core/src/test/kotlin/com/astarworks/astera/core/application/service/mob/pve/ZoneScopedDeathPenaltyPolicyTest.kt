package com.astarworks.astera.core.application.service.mob.pve

import com.astarworks.astera.core.application.service.mob.InMemoryEncounterZoneRegistry
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.mob.MobRegionId
import com.astarworks.astera.core.domain.mob.encounter.DeathPenaltyRule
import com.astarworks.astera.core.domain.mob.encounter.EncounterZone
import com.astarworks.astera.core.domain.mob.encounter.EncounterZoneId
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class ZoneScopedDeathPenaltyPolicyTest {

    private val playerId = PlayerId(UUID.randomUUID())

    private fun zone(
        id: String,
        penalty: DeathPenaltyRule = DeathPenaltyRule.SafeDeath,
    ) = EncounterZone(
        id = EncounterZoneId(id),
        displayName = LocalizedText(mapOf(LocaleCode.EN to "Zone")),
        region = MobRegionId("test"),
        mobPool = emptyList(),
        maxConcurrent = 0,
        deathPenalty = penalty,
    )

    @Test
    fun `null zone id resolves to SAFE`() {
        val registry = InMemoryEncounterZoneRegistry()
        val policy = ZoneScopedDeathPenaltyPolicy(registry)
        assertThat(policy.ruleFor(playerId, zoneId = null))
            .isEqualTo(DeathPenaltyRule.SAFE)
    }

    @Test
    fun `unknown zone id resolves to SAFE`() {
        val registry = InMemoryEncounterZoneRegistry()
        val policy = ZoneScopedDeathPenaltyPolicy(registry)
        assertThat(policy.ruleFor(playerId, EncounterZoneId("astera:nonexistent")))
            .isEqualTo(DeathPenaltyRule.SAFE)
    }

    @Test
    fun `known zone returns its declared SafeDeath rule`() {
        val registry = InMemoryEncounterZoneRegistry()
        registry.register(zone("astera:safe-zone", DeathPenaltyRule.SafeDeath))
        val policy = ZoneScopedDeathPenaltyPolicy(registry)
        assertThat(policy.ruleFor(playerId, EncounterZoneId("astera:safe-zone")))
            .isEqualTo(DeathPenaltyRule.SafeDeath)
    }

    @Test
    fun `known zone with item-drop rule returns it intact (grace ticks preserved)`() {
        val registry = InMemoryEncounterZoneRegistry()
        val penalty = DeathPenaltyRule.ItemDropOnDeath(graceTicks = 1200)
        registry.register(zone("astera:hard-zone", penalty))
        val policy = ZoneScopedDeathPenaltyPolicy(registry)
        val resolved = policy.ruleFor(playerId, EncounterZoneId("astera:hard-zone"))
        assertThat(resolved).isInstanceOf(DeathPenaltyRule.ItemDropOnDeath::class.java)
        assertThat((resolved as DeathPenaltyRule.ItemDropOnDeath).graceTicks).isEqualTo(1200)
    }
}
