package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.domain.damage.DamageEntry
import com.astarworks.astera.combat.domain.damage.DamageHistoryWindow
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryDamageHistoryServiceTest {

    private val target = PlayerId(UUID.randomUUID())
    private val a = PlayerId(UUID.randomUUID())
    private val b = PlayerId(UUID.randomUUID())
    private val c = PlayerId(UUID.randomUUID())

    @Test
    fun `record then entries returns oldest-first`() {
        val svc = InMemoryDamageHistoryService()
        svc.record(target, DamageEntry(a, 5.0, at = 10))
        svc.record(target, DamageEntry(b, 3.0, at = 20))
        val entries = svc.entries(target)
        assertThat(entries).hasSize(2)
        assertThat(entries.map { it.attackerId }).containsExactly(a, b)
    }

    @Test
    fun `recentAttackers returns distinct in-window attackers`() {
        val svc = InMemoryDamageHistoryService()
        svc.record(target, DamageEntry(a, 5.0, at = 10))
        svc.record(target, DamageEntry(b, 3.0, at = 15))
        svc.record(target, DamageEntry(a, 1.0, at = 18)) // dup attacker
        val result = svc.recentAttackers(target, DamageHistoryWindow(durationTicks = 100), asOf = 20)
        assertThat(result).containsExactlyInAnyOrder(a, b)
    }

    @Test
    fun `recentAttackers excludes entries older than window`() {
        val svc = InMemoryDamageHistoryService()
        svc.record(target, DamageEntry(a, 5.0, at = 100))  // 100 ticks ago, just inside
        svc.record(target, DamageEntry(b, 3.0, at = 50))   // > window, dropped
        val result = svc.recentAttackers(target, DamageHistoryWindow(durationTicks = 100), asOf = 200)
        assertThat(result).containsExactly(a)
    }

    @Test
    fun `clear drops every entry for the target`() {
        val svc = InMemoryDamageHistoryService()
        svc.record(target, DamageEntry(a, 5.0, at = 10))
        svc.clear(target)
        assertThat(svc.entries(target)).isEmpty()
        assertThat(svc.recentAttackers(target, DamageHistoryWindow.DEFAULT, asOf = 200)).isEmpty()
    }

    @Test
    fun `ring buffer caps per-target entries at the configured size`() {
        val svc = InMemoryDamageHistoryService(capPerTarget = 4)
        repeat(6) { i -> svc.record(target, DamageEntry(c, 1.0, at = i.toLong())) }
        val entries = svc.entries(target)
        assertThat(entries).hasSize(4)
        // oldest entries (at=0, at=1) should have been popped
        assertThat(entries.first().at).isEqualTo(2L)
        assertThat(entries.last().at).isEqualTo(5L)
    }
}
