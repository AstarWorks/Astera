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
    fun `ResourceType all has three variants in fixed order`() {
        assertThat(ResourceType.all).containsExactly(
            ResourceType.Ammo,
            ResourceType.Stamina,
            ResourceType.Mana,
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
        assertThat(ResourceType.fromKey("Stamina")).isEqualTo(ResourceType.Stamina)
        assertThat(ResourceType.fromKey("mana")).isEqualTo(ResourceType.Mana)
    }

    @Test
    fun `ResourceType fromKey returns null for unknown`() {
        assertThat(ResourceType.fromKey("heat")).isNull()
    }

    @Test
    fun `PlayerResourcePool rejects negative counters`() {
        assertThatThrownBy {
            PlayerResourcePool(pid, null, ammo = -1, stamina = 0, mana = 0)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            PlayerResourcePool(pid, null, ammo = 0, stamina = -5, mana = 0)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `PlayerResourcePool valueOf returns the right counter`() {
        val pool = PlayerResourcePool(pid, null, ammo = 5, stamina = 10, mana = 20)
        assertThat(pool.valueOf(ResourceType.Ammo)).isEqualTo(5)
        assertThat(pool.valueOf(ResourceType.Stamina)).isEqualTo(10)
        assertThat(pool.valueOf(ResourceType.Mana)).isEqualTo(20)
    }

    @Test
    fun `PlayerResourcePool with clamps negative to zero`() {
        val pool = PlayerResourcePool(pid, null, ammo = 5, stamina = 10, mana = 20)
        val updated = pool.with(ResourceType.Mana, -7)
        assertThat(updated.mana).isEqualTo(0)
    }

    @Test
    fun `PlayerResourcePool with preserves other counters`() {
        val pool = PlayerResourcePool(pid, ItemId("test"), ammo = 5, stamina = 10, mana = 20)
        val updated = pool.with(ResourceType.Stamina, 50)
        assertThat(updated.ammo).isEqualTo(5)
        assertThat(updated.stamina).isEqualTo(50)
        assertThat(updated.mana).isEqualTo(20)
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
