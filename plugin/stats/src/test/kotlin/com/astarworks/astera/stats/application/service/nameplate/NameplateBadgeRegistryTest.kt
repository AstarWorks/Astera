package com.astarworks.astera.stats.application.service.nameplate

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.domain.nameplate.NameplateBadge
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class NameplateBadgeRegistryTest {

    private val player = PlayerId(UUID.randomUUID())

    @Test
    fun `empty registry returns null on find`() {
        assertThat(NameplateBadgeRegistry().find(player)).isNull()
    }

    @Test
    fun `resolveOrEmpty falls back to empty badge`() {
        val r = NameplateBadgeRegistry()
        val b = r.resolveOrEmpty(player)
        assertThat(b.playerId).isEqualTo(player)
        assertThat(b.isEmpty).isTrue()
    }

    @Test
    fun `put then find returns the put badge`() {
        val r = NameplateBadgeRegistry()
        val b = NameplateBadge.empty(player).copy(powerIndexTotal = 42L)
        r.put(b)
        assertThat(r.find(player)).isEqualTo(b)
    }

    @Test
    fun `evict drops the entry`() {
        val r = NameplateBadgeRegistry()
        r.put(NameplateBadge.empty(player))
        r.evict(player)
        assertThat(r.find(player)).isNull()
    }
}
