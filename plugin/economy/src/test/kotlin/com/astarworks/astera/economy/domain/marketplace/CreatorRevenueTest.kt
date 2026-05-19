package com.astarworks.astera.economy.domain.marketplace

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

internal class CreatorRevenueTest {

    private val item = ItemId("rare-blade")
    private val creator = PlayerId(UUID.randomUUID())

    @Test
    fun `accepts revenueShare 0_0`() {
        val record = CreatorRevenue(item, creator, 0.0)
        assertThat(record.revenueShare).isEqualTo(0.0)
    }

    @Test
    fun `accepts revenueShare 1_0`() {
        val record = CreatorRevenue(item, creator, 1.0)
        assertThat(record.revenueShare).isEqualTo(1.0)
    }

    @Test
    fun `accepts a typical mid-range share`() {
        val record = CreatorRevenue(item, creator, 0.15)
        assertThat(record.revenueShare).isEqualTo(0.15)
    }

    @Test
    fun `rejects negative revenueShare`() {
        assertThatThrownBy { CreatorRevenue(item, creator, -0.0001) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("revenueShare must be in [0.0, 1.0]")
    }

    @Test
    fun `rejects revenueShare above 1_0`() {
        assertThatThrownBy { CreatorRevenue(item, creator, 1.0001) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("revenueShare must be in [0.0, 1.0]")
    }
}
