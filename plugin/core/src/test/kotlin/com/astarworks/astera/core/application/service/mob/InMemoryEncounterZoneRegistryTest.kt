package com.astarworks.astera.core.application.service.mob

import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.mob.MobRegionId
import com.astarworks.astera.core.domain.mob.MobTypeId
import com.astarworks.astera.core.domain.mob.encounter.DifficultyCurve
import com.astarworks.astera.core.domain.mob.encounter.EncounterMobEntry
import com.astarworks.astera.core.domain.mob.encounter.EncounterZone
import com.astarworks.astera.core.domain.mob.encounter.EncounterZoneId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InMemoryEncounterZoneRegistryTest {

    private fun zone(id: String, weight: Int = 1) = EncounterZone(
        id = EncounterZoneId(id),
        displayName = LocalizedText(mapOf(LocaleCode.EN to id)),
        region = MobRegionId(id.substringAfter(":")),
        mobPool = listOf(EncounterMobEntry(MobTypeId("astera:wolf"), weight = weight)),
        maxConcurrent = 5,
        difficulty = DifficultyCurve.Flat,
    )

    @Test
    fun `register then get returns the same zone`() {
        val reg = InMemoryEncounterZoneRegistry()
        val z = zone("astera:zone-a")
        reg.register(z)
        assertThat(reg.get(EncounterZoneId("astera:zone-a"))).isSameAs(z)
        assertThat(reg.size()).isEqualTo(1)
    }

    @Test
    fun `all returns insertion order`() {
        val reg = InMemoryEncounterZoneRegistry()
        reg.register(zone("astera:zone-a"))
        reg.register(zone("astera:zone-b"))
        reg.register(zone("astera:zone-c"))
        val ids = reg.all().map { it.id.value }
        assertThat(ids).containsExactly("astera:zone-a", "astera:zone-b", "astera:zone-c")
    }

    @Test
    fun `re-register overwrites`() {
        val reg = InMemoryEncounterZoneRegistry()
        reg.register(zone("astera:zone-a", weight = 1))
        reg.register(zone("astera:zone-a", weight = 7))
        assertThat(reg.get(EncounterZoneId("astera:zone-a"))?.mobPool?.first()?.weight)
            .isEqualTo(7)
        assertThat(reg.size()).isEqualTo(1)
    }

    @Test
    fun `unknown id returns null`() {
        val reg = InMemoryEncounterZoneRegistry()
        assertThat(reg.get(EncounterZoneId("astera:unknown"))).isNull()
    }
}
