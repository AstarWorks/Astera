package com.astarworks.astera.economy.domain.power

import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class PowerIndexTest {

    private val player = PlayerId(UUID.randomUUID())
    private val now = Instant.parse("2026-05-20T00:00:00Z")

    @Test
    fun `total is sum of every component`() {
        val px = PowerIndex(
            playerId = player,
            equipmentValue = 100L,
            vaultValue = 200L,
            walletValue = 300L,
            marketplaceListingValue = 400L,
            computedAt = now,
        )
        assertThat(px.total).isEqualTo(1000L)
    }

    @Test
    fun `empty snapshot totals zero`() {
        val px = PowerIndex.empty(player, now)
        assertThat(px.total).isZero()
        assertThat(px.equipmentValue).isZero()
        assertThat(px.vaultValue).isZero()
        assertThat(px.walletValue).isZero()
        assertThat(px.marketplaceListingValue).isZero()
    }

    @Test
    fun `negative equipment rejected`() {
        assertThatThrownBy {
            PowerIndex(player, equipmentValue = -1L, 0, 0, 0, now)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `negative wallet rejected`() {
        assertThatThrownBy {
            PowerIndex(player, 0, 0, walletValue = -1L, 0, now)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `negative vault rejected`() {
        assertThatThrownBy {
            PowerIndex(player, 0, vaultValue = -1L, 0, 0, now)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `negative marketplace rejected`() {
        assertThatThrownBy {
            PowerIndex(player, 0, 0, 0, marketplaceListingValue = -1L, now)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `total saturates at Long MAX_VALUE on overflow`() {
        val px = PowerIndex(
            playerId = player,
            equipmentValue = Long.MAX_VALUE - 10,
            vaultValue = 100L,
            walletValue = 0L,
            marketplaceListingValue = 0L,
            computedAt = now,
        )
        assertThat(px.total).isEqualTo(Long.MAX_VALUE)
    }

    @Test
    fun `saturatingAdd zero short-circuits`() {
        assertThat(PowerIndex.saturatingAdd(0L, 7L)).isEqualTo(7L)
        assertThat(PowerIndex.saturatingAdd(7L, 0L)).isEqualTo(7L)
    }

    @Test
    fun `saturatingAdd normal case`() {
        assertThat(PowerIndex.saturatingAdd(3L, 4L)).isEqualTo(7L)
    }

    @Test
    fun `saturatingAdd overflow returns Long MAX_VALUE`() {
        assertThat(PowerIndex.saturatingAdd(Long.MAX_VALUE, 1L)).isEqualTo(Long.MAX_VALUE)
        assertThat(PowerIndex.saturatingAdd(Long.MAX_VALUE - 1, 5L)).isEqualTo(Long.MAX_VALUE)
    }
}
