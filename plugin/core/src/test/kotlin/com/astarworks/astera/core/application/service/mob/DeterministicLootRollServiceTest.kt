package com.astarworks.astera.core.application.service.mob

import com.astarworks.astera.core.domain.event.LootDropped
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.mob.LootTableId
import com.astarworks.astera.core.domain.mob.MobTypeId
import com.astarworks.astera.core.domain.mob.boss.BossId
import com.astarworks.astera.core.domain.mob.loot.LootDrop
import com.astarworks.astera.core.domain.mob.loot.LootRollContext
import com.astarworks.astera.core.domain.mob.runtime.MobId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class DeterministicLootRollServiceTest {

    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var service: DeterministicLootRollService

    private val killer = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val mob = MobId.of(UUID.fromString("00000000-0000-0000-0000-000000000002"))
    private val mobType = MobTypeId("astera:test-mob")
    private val table = LootTableId("astera:rare-drops")
    private val gold = ItemId("gold")
    private val sword = ItemId("legendary-sword")

    @BeforeEach
    fun setup() {
        eventBus = FakeDomainEventBus()
        service = DeterministicLootRollService(eventBus)
    }

    private fun ctx(bossId: BossId? = null) = LootRollContext(
        tableId = table,
        killer = killer,
        mobId = mob,
        mobTypeId = mobType,
        bossId = bossId,
    )

    @Test
    fun `unknown table returns empty roll`() {
        val roll = service.roll(ctx(), seed = 1L, atTick = 0L)
        assertThat(roll.isEmpty).isTrue
        assertThat(eventBus.publishedOfType<LootDropped>()).hasSize(1)
        assertThat(eventBus.publishedOfType<LootDropped>()[0].drops).isEmpty()
    }

    @Test
    fun `guaranteed drop always fires`() {
        service.registerTable(table, listOf(LootDrop(gold, chance = 1.0)))
        val roll = service.roll(ctx(), seed = 42L, atTick = 0L)
        assertThat(roll.drops).containsExactly(gold to 1)
    }

    @Test
    fun `zero-chance drop never fires`() {
        service.registerTable(table, listOf(LootDrop(gold, chance = 0.0)))
        val roll = service.roll(ctx(), seed = 42L, atTick = 0L)
        assertThat(roll.isEmpty).isTrue
    }

    @Test
    fun `same context and seed produce same drops`() {
        service.registerTable(
            table,
            listOf(
                LootDrop(gold, chance = 0.5, minCount = 1, maxCount = 5),
                LootDrop(sword, chance = 0.001),
            ),
        )
        val a = service.roll(ctx(), seed = 12345L, atTick = 0L)
        val b = service.roll(ctx(), seed = 12345L, atTick = 0L)
        assertThat(a.drops).isEqualTo(b.drops)
        assertThat(a.seed).isEqualTo(b.seed)
    }

    @Test
    fun `different seeds produce different distributions across many rolls`() {
        service.registerTable(
            table,
            listOf(LootDrop(gold, chance = 0.5)),
        )
        val histA = (1..200).count {
            service.roll(ctx(), seed = it.toLong(), atTick = 0L).drops.isNotEmpty()
        }
        val histB = (201..400).count {
            service.roll(ctx(), seed = it.toLong(), atTick = 0L).drops.isNotEmpty()
        }
        // Sanity: both should land near the 100 expected (binomial sigma ~7,
        // ±5 sigma window). Looser than that and the RNG is broken.
        assertThat(histA).isBetween(60, 140)
        assertThat(histB).isBetween(60, 140)
    }

    @Test
    fun `range count produces value within bounds`() {
        service.registerTable(table, listOf(LootDrop(gold, chance = 1.0, minCount = 2, maxCount = 4)))
        repeat(50) {
            val roll = service.roll(ctx(), seed = it.toLong(), atTick = 0L)
            val count = roll.drops.single { (id, _) -> id == gold }.second
            assertThat(count).isBetween(2, 4)
        }
    }

    @Test
    fun `boss context flagged on LootDropped event`() {
        service.registerTable(table, listOf(LootDrop(gold, chance = 1.0)))
        service.roll(ctx(bossId = BossId("astera:lich")), seed = 1L, atTick = 99L)
        val event = eventBus.publishedOfType<LootDropped>().single()
        assertThat(event.isBossDrop).isTrue
        assertThat(event.atTick).isEqualTo(99L)
    }

    @Test
    fun `multiple rolls accumulate same itemId`() {
        service.registerTable(
            table,
            listOf(
                LootDrop(gold, chance = 1.0, minCount = 1, maxCount = 1),
                LootDrop(gold, chance = 1.0, minCount = 2, maxCount = 2),
            ),
        )
        val roll = service.roll(ctx(), seed = 1L, atTick = 0L)
        assertThat(roll.drops).containsExactly(gold to 3)
    }
}
