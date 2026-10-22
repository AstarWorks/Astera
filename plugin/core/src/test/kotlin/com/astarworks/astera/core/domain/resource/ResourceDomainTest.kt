package com.astarworks.astera.core.domain.resource

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wave 5k (ADR-0080) — Resource domain value objects.
 */
class ResourceDomainTest {

    private val pid = PlayerId(UUID.randomUUID())

    @Test
    fun `ResourceType all has two variants in fixed order`() {
        assertThat(ResourceType.all).containsExactly(
            ResourceType.Ammo,
            ResourceType.Energy,
        )
    }

    @Test
    fun `ResourceType key round-trips through fromKey`() {
        for (t in ResourceType.all) {
            assertThat(ResourceType.fromKey(t.key)).isEqualTo(t)
        }
    }

    @Test
    fun `ResourceType fromKey is case-insensitive`() {
        assertThat(ResourceType.fromKey("AMMO")).isEqualTo(ResourceType.Ammo)
        assertThat(ResourceType.fromKey("Energy")).isEqualTo(ResourceType.Energy)
    }

    @Test
    fun `ResourceType fromKey returns null for unknown`() {
        assertThat(ResourceType.fromKey("heat")).isNull()
        // The retired names are gone for good (no alias, ADR-0194).
        assertThat(ResourceType.fromKey("mana")).isNull()
        assertThat(ResourceType.fromKey("stamina")).isNull()
    }

    @Test
    fun `PlayerResourcePool rejects negative counters`() {
        assertThatThrownBy {
            PlayerResourcePool(pid, null, ammo = -1, energy = 0)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            PlayerResourcePool(pid, null, ammo = 0, energy = -5)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `PlayerResourcePool valueOf returns the right counter`() {
        val pool = PlayerResourcePool(pid, null, ammo = 5, energy = 20)
        assertThat(pool.valueOf(ResourceType.Ammo)).isEqualTo(5)
        assertThat(pool.valueOf(ResourceType.Energy)).isEqualTo(20)
    }

    @Test
    fun `PlayerResourcePool with clamps negative to zero`() {
        val pool = PlayerResourcePool(pid, null, ammo = 5, energy = 20)
        val updated = pool.with(ResourceType.Energy, -7)
        assertThat(updated.energy).isEqualTo(0)
    }

    @Test
    fun `PlayerResourcePool with preserves other counters`() {
        val pool = PlayerResourcePool(pid, ItemId("test"), ammo = 5, energy = 20)
        val updated = pool.with(ResourceType.Energy, 50)
        assertThat(updated.ammo).isEqualTo(5)
        assertThat(updated.energy).isEqualTo(50)
        assertThat(updated.activeWeapon).isEqualTo(ItemId("test"))
    }

    @Test
    fun `ResourceCap rejects negative max or non-finite regen`() {
        assertThatThrownBy { ResourceCap(maxPool = -1, regenPerSecond = 1.0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ResourceCap(maxPool = 1, regenPerSecond = -1.0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ResourceCap(maxPool = 1, regenPerSecond = Double.NaN) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `ResourceCap DEFAULTS covers all three types`() {
        assertThat(ResourceCap.DEFAULTS.keys).containsExactlyInAnyOrderElementsOf(ResourceType.all)
    }
}
